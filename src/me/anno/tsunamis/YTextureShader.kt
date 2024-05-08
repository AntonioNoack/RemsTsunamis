package me.anno.tsunamis

import me.anno.engine.ui.render.ECSMeshShader
import me.anno.engine.ui.render.RendererLib.getReflectivity
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.ShaderLib.brightness
import me.anno.gpu.shader.ShaderLib.parallaxMapping
import me.anno.gpu.shader.ShaderLib.quatRot
import me.anno.gpu.shader.builder.Function
import me.anno.gpu.shader.builder.ShaderStage
import me.anno.gpu.shader.builder.Variable
import me.anno.gpu.shader.builder.VariableMode
import me.anno.utils.types.Booleans.hasFlag

class YTextureShader private constructor(private val halfPrecision: Boolean) : ECSMeshShader("YTexture") {

    private fun createVertexVariables(): ArrayList<Variable> {
        val list = ArrayList<Variable>()
        list.add(Variable(GLSLType.M4x3, "localTransform"))
        if (halfPrecision) {
            list.add(Variable(GLSLType.S2D, "fluidSurface"))
            list.add(Variable(GLSLType.S2D, "fluidMomentumX"))
            list.add(Variable(GLSLType.S2D, "fluidMomentumY"))
            list.add(Variable(GLSLType.S2D, "fluidBathymetry"))
        } else {
            list.add(Variable(GLSLType.S2D, "fluidData"))
        }
        list.add(Variable(GLSLType.V4F, "fluidDataI", VariableMode.OUT))
        list.add(Variable(GLSLType.S2D, "colorMap"))
        list.add(Variable(GLSLType.V2F, "colorMapScale"))
        list.add(Variable(GLSLType.V4F, "visualMask"))
        list.add(Variable(GLSLType.V1F, "visScale"))
        list.add(Variable(GLSLType.V1I, "visualization"))
        list.add(Variable(GLSLType.V1F, "cellSize"))
        list.add(Variable(GLSLType.V3F, "cellOffset"))
        list.add(Variable(GLSLType.V4F, "heightMask"))
        list.add(Variable(GLSLType.V1F, "fluidHeightScale"))
        list.add(Variable(GLSLType.V2I, "coarseSize"))
        list.add(Variable(GLSLType.V1B, "nearestNeighborColors"))
        return list
    }

    private val getColorFunc = Function(
        "", "color-func",
        "" +
                "vec3 getColor11(float v){\n" +
                "   return v < 0.0 ?\n" +
                "       mix(vec3(0.0,0.33,1.0), vec3(1.0), clamp(v+1.0, 0.0, 1.0)) :\n" +
                "       mix(vec3(1.0), vec3(1.0,0.0,0.0), clamp(v, 0.0, 1.0));\n" +
                "}\n" +
                "vec4 getColor(vec4 data){\n" +
                "   float colorMapValue = clamp(data.a * colorMapScale.x + colorMapScale.y, 0.0, 1.0);\n" +
                "   int   colorMapSize  = textureSize(colorMap, 0).x;\n" +
                "   vec4  rawColor      = texelFetch(colorMap, ivec2(min(int(float(colorMapSize) * colorMapValue), colorMapSize-1), 0), 0);\n" +
                "   vec4  vertexColor   = vec4(rawColor.rgb, 1.0);\n" +
                "   if(data.a < 0.0 && visualization != ${Visualisation.HEIGHT_MAP.id}){\n" +
                "       float v = 0;\n" +
                "       switch(visualization){\n" +
                "       case ${Visualisation.MOMENTUM.id}:\n" +
                "           v = length(vec2(data.gb)) * visScale;\n" +
                "           break;\n" +
                "       default:\n" +
                "           v = dot(visualMask, data);\n" +
                "           break;\n" +
                "       }\n" +
                "       return vec4(getColor11(v), 1.0);\n" +
                "   }\n" +
                "   return vertexColor;\n" +
                "}\n"
    )

    private val getPixelFunc = Function(
        "", "pixel-func",
        "" +
                "vec4 getFluidData(ivec2 uv, ivec2 fieldSizeM1){\n" +
                "   int lod = 0;\n" +
                "   uv = clamp(uv, ivec2(0), fieldSizeM1);\n" +
                (if (halfPrecision) {
                    "" +
                            "float surf      = texelFetch(fluidSurface, uv, lod).x;\n" +
                            "float momentumX = texelFetch(fluidMomentumX, uv, lod).x;\n" +
                            "float momentumY = texelFetch(fluidMomentumY, uv, lod).x;\n" +
                            "float bath      = texelFetch(fluidBathymetry, uv, lod).x;\n" +
                            "return vec4(surf - bath, momentumX, momentumY, bath);\n"
                } else {
                    "return texelFetch(fluidData, uv, lod);\n"
                }) +
                "};\n"
    )

    override fun createVertexStages(key: ShaderKey): List<ShaderStage> {
        return super.createDefines(key) + ShaderStage(
            "vertex", createVertexVariables() + listOf(
                Variable(GLSLType.V3F, "localPosition", VariableMode.OUT),
                Variable(GLSLType.V3F, "finalPosition", VariableMode.OUT),
                Variable(GLSLType.V4F, "currPosition", VariableMode.OUT),
                Variable(GLSLType.V4F, "prevPosition", VariableMode.OUT),
                Variable(GLSLType.V3F, "normal", VariableMode.OUT),
                Variable(GLSLType.V4F, "tangent", VariableMode.OUT),
                Variable(GLSLType.V4F, "vertexColor0", VariableMode.OUT),
                Variable(GLSLType.M4x3, "localTransform"),
                Variable(GLSLType.M4x4, "prevTransform"),
                Variable(GLSLType.M4x4, "transform"),
            ),
            "" +
                    // create x,z coordinates from vertex index
                    // create y,normal from texture data
                    "int lod = 0;\n" +
                    "#define SURFACE(h) dot(h, heightMask)\n" +
                    "#define SURFACE2(h) dot(h, heightMask) * (h.r > 0.0 ? fluidHeightScale : 1.0)\n" +
                    // coarse index to fine index, without ghost cells
                    "#define COARSE_INDEX_TO_FINE1(x,w,cw) w == cw || x < 1 || cw <= 2 ? x : cw - x <= 1 ? x + w - cw : 1 + (x-1)*(w-2)/(cw-2)\n" +
                    "ivec2 fieldSize = textureSize(${if (halfPrecision) "fluidSurface" else "fluidData"}, lod);\n" +
                    "int instanceId = int(gl_InstanceID);\n" +
                    "int cellIndex  = instanceId >> 1;\n" +
                    "int partOfCell = clamp(gl_VertexID + (instanceId & 1) * 3, 0, 5);\n" +
                    "int deltaX[6] = int[](0, 1, 1, 1, 0, 0);\n" +
                    "int deltaY[6] = int[](0, 1, 0, 1, 0, 1);\n" +
                    "int numCellsX = max(1, coarseSize.x - 1);\n" +
                    "int numCellsY = max(1, coarseSize.y - 1);\n" +
                    "ivec2 numCells = ivec2(numCellsX, numCellsY);\n" +
                    "int cellX0 = cellIndex % numCellsX;\n" +
                    "int cellY0 = cellIndex / numCellsX;\n" +
                    "int cellXi = cellX0 + deltaX[partOfCell];\n" +
                    "int cellYi = cellY0 + deltaY[partOfCell];\n" +
                    "ivec2 fieldSizeM1 = fieldSize - 1;\n" +
                    "if(coarseSize.x > 0 && coarseSize.x != fieldSize.x){\n" +
                    "   cellXi = COARSE_INDEX_TO_FINE1(cellXi, fieldSize.x, coarseSize.x);\n" +
                    "   cellYi = COARSE_INDEX_TO_FINE1(cellYi, fieldSize.y, coarseSize.y);\n" +
                    "}\n" +
                    "ivec2 cell = ivec2(cellXi, cellYi);\n" +
                    "vec2 invFieldSize = 1.0 / vec2(fieldSizeM1);\n" +
                    "vec2 uv = vec2(cellXi, cellYi) * invFieldSize;\n" + // [0,1]
                    "vec4 data = getFluidData(cell, fieldSizeM1);\n" +
                    "localPosition = vec3(float(cellXi) * cellSize, SURFACE2(data), float(cellYi) * cellSize) + cellOffset;\n" +
                    "prevPosition = vec4(localPosition,1.0);\n" +
                    "#ifdef COLORS\n" +
                    // calculate the normals
                    "   vec4 dxp = getFluidData(cell + ivec2(1, 0), fieldSizeM1);\n" +
                    "   vec4 dxm = getFluidData(cell - ivec2(1, 0), fieldSizeM1);\n" +
                    "   vec4 dyp = getFluidData(cell + ivec2(0, 1), fieldSizeM1);\n" +
                    "   vec4 dym = getFluidData(cell - ivec2(0, 1), fieldSizeM1);\n" +
                    "   vec3 normals = normalize(vec3(\n" +
                    "       SURFACE2(dxp) - SURFACE2(dxm),\n" +
                    "       cellSize * 2.0,\n" +
                    "       SURFACE2(dyp) - SURFACE2(dym)\n" +
                    "   ));\n" +
                    // should be done more properly, but it's only used for effects we don't need, so it doesn't really matter
                    "   vec4 tangents = vec4(1.0, 0.0, 0.0, 0.0);\n" +
                    "   normal = normalize(matMul(localTransform, vec4(normals, 0.0)));\n" +
                    "   tangent.xyz = normalize(matMul(localTransform, vec4(tangents.xyz, 0.0)));\n" +
                    "   if(nearestNeighborColors){\n" +
                    "       if(coarseSize.x > 0 && coarseSize.x != fieldSize.x){\n" +
                    "           cellX0 = COARSE_INDEX_TO_FINE1(cellX0, fieldSize.x, coarseSize.x);\n" +
                    "           cellY0 = COARSE_INDEX_TO_FINE1(cellY0, fieldSize.y, coarseSize.y);\n" +
                    "       }\n" +
                    "       data = getFluidData(ivec2(cellX0, cellY0), fieldSizeM1);\n" +
                    "       vertexColor0 = getColor(data);\n" + // no interpolation required
                    "   } else {\n" +
                    "       fluidDataI = data;\n" +
                    "   }\n" +
                    "   uv = vec2(0.0);\n" +
                    "#endif\n" +
                    "finalPosition = matMul(localTransform, vec4(localPosition, 1.0));\n" +
                    glPositionCode + motionVectorCode
        ).add(getColorFunc).add(getPixelFunc)
    }

    override fun createFragmentStages(key: ShaderKey): List<ShaderStage> {
        return key.vertexData.onFragmentShader + listOf(
            ShaderStage(
                "material",
                createFragmentVariables(key) + listOf(
                    Variable(GLSLType.V4F, "cameraRotation"),
                    Variable(GLSLType.S2D, "colorMap"),
                    Variable(GLSLType.V2F, "colorMapScale"),
                    Variable(GLSLType.V1I, "visualization"),
                    Variable(GLSLType.V1F, "visScale"),
                    Variable(GLSLType.V4F, "vertexColor0"),
                    Variable(GLSLType.V4F, "fluidDataI"),
                    Variable(GLSLType.V1B, "nearestNeighborColors"),
                    Variable(GLSLType.V4F, "visualMask")
                ),
                concatDefines(key).toString() +
                        discardByCullingPlane +
                        // step by step define all material properties
                        (if (key.flags.hasFlag(NEEDS_COLORS)) {
                            "vec4 color = nearestNeighborColors ? vec4(vertexColor0.rgb, 1.0) : getColor(fluidDataI);\n" +
                                    "finalColor = color.rgb;\n" +
                                    "finalAlpha = color.a;\n" +
                                    normalTanBitanCalculation +
                                    normalMapCalculation +
                                    emissiveCalculation +
                                    occlusionCalculation +
                                    metallicCalculation +
                                    roughnessCalculation +
                                    v0 + sheenCalculation +
                                    clearCoatCalculation +
                                    reflectionCalculation
                        } else "") +
                        finalMotionCalculation
            ).add(quatRot).add(brightness).add(parallaxMapping).add(getReflectivity).add(getColorFunc)
        )
    }

    init {
        glslVersion = 330
    }

    companion object {
        val rgbaShader = YTextureShader(false)
        val r16fShader = YTextureShader(true)
    }

}