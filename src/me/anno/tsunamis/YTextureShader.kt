package me.anno.tsunamis

import me.anno.engine.ui.render.ECSMeshShader
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.Function
import me.anno.gpu.shader.builder.ShaderStage
import me.anno.gpu.shader.builder.Variable
import me.anno.gpu.shader.builder.VariableMode

class YTextureShader private constructor(private val halfPrecision: Boolean) : ECSMeshShader("YTexture") {

    override fun createVertexAttributes(instanced: Boolean, colors: Boolean): ArrayList<Variable> {
        val list = super.createVertexAttributes(instanced, colors)
        list.removeIf {
            when (it.name) {
                "coords",
                "normals",
                "tangents",
                "uvs",
                "vertexColors" -> true
                else -> false
            }
        }
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
        list.add(Variable(GLSLType.BOOL, "nearestNeighborColors", VariableMode.IN))
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

    override fun createVertexStage(instanced: Boolean, colors: Boolean): ShaderStage {

        val defines = "" +
                (if (instanced) "#define INSTANCED\n" else "") +
                (if (colors) "#define COLORS\n" else "")

        glslVersion = 330

        // done coarsening on gpu side: scale down image, or just use it as-is with strided access?
        // (to do maybe) scaling down the image should help performance, when not every frame is rendered, or shadows are needed

        return ShaderStage(
            "vertex",
            createVertexAttributes(instanced, colors),
            "" +
                    defines +
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
                    "int partOfCell = gl_VertexID + (instanceId & 1) * 3;\n" +
                    "int deltaX[6] = { 0, 1, 1, 1, 0, 0 };\n" +
                    "int deltaY[6] = { 0, 1, 0, 1, 0, 1 };\n" +
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
                    "vec2 invFieldSize = 1.0 / vec2(fieldSize-1);\n" +
                    "vec2 uv = vec2(cellXi, cellYi) * invFieldSize;\n" + // [0,1]
                    "vec4 data = getFluidData(cell, fieldSizeM1);\n" +
                    "localPosition = vec3(float(cellXi) * cellSize, SURFACE2(data), float(cellYi) * cellSize) + cellOffset;\n" +
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
                    "   vec3 tangents = vec3(1.0, 0.0, 0.0);\n" +
                    "#endif\n" +
                    // instanced is not supported
                    "finalPosition = localTransform * vec4(localPosition, 1.0);\n" +
                    "#ifdef COLORS\n" +
                    "   normal  = normalize(localTransform * vec4(normals, 0.0));\n" +
                    "   tangent = normalize(localTransform * vec4(tangents, 0.0));\n" +
                    "   if(nearestNeighborColors){\n" +
                    "       if(coarseSize.x > 0 && coarseSize.x != fieldSize.x){\n" +
                    "           cellX0 = COARSE_INDEX_TO_FINE1(cellX0, fieldSize.x, coarseSize.x);\n" +
                    "           cellY0 = COARSE_INDEX_TO_FINE1(cellY0, fieldSize.y, coarseSize.y);\n" +
                    "       }\n" +
                    "       data = getFluidData(ivec2(cellX0, cellY0), fieldSizeM1);\n" +
                    "       vertexColor = getColor(data);\n" + // no interpolation required
                    "   } else {\n" +
                    "       fluidDataI = data;\n" +
                    "   }\n" +
                    "   uv = vec2(0.0);\n" +
                    "#endif\n" +
                    "gl_Position = transform * vec4(finalPosition, 1.0);\n" +
                    ShaderLib.positionPostProcessing
        ).apply {
            functions.add(getColorFunc)
            functions.add(getPixelFunc)
        }
    }

    override fun createFragmentStage(instanced: Boolean): ShaderStage {

        // copied from super mainly

        val original = super.createFragmentStage(instanced)

        val fragmentVariables = original.variables + listOf(
            Variable(GLSLType.BOOL, "nearestNeighborColors", VariableMode.IN),
            Variable(GLSLType.V4F, "fluidDataI", VariableMode.IN),
            Variable(GLSLType.S2D, "colorMap"),
            Variable(GLSLType.V2F, "colorMapScale"),
            Variable(GLSLType.V4F, "visualMask"),
            Variable(GLSLType.V1F, "visScale"),
            Variable(GLSLType.V1I, "visualization"),
            Variable(GLSLType.BOOL, "halfTransparent")
        )

        return ShaderStage(
            "material", fragmentVariables, "" +
                    "if(halfTransparent && (int(gl_FragCoord.x + gl_FragCoord.y) & 1) == 0) discard;\n" +
                    "if(dot(vec4(finalPosition, 1.0), reflectionCullingPlane) < 0.0) discard;\n" +

                    // step by step define all material properties
                    "vec4 color = nearestNeighborColors ? vec4(vertexColor.rgb, 1.0) : getColor(fluidDataI);\n" +
                    // "color *= diffuseBase * texture(diffuseMap, uv);\n" +
                    // "if(color.a < ${1f / 255f}) discard;\n" +
                    "finalColor = color.rgb;\n" +
                    "finalAlpha = color.a;\n" +
                    // "   vec3 finalNormal = normal;\n" +
                    "finalTangent   = normalize(tangent);\n" + // for debugging
                    "finalNormal    = normalize(normal);\n" +
                    "finalBitangent = normalize(cross(finalNormal, finalTangent));\n" +
                    // bitangent: checked, correct transform
                    // can be checked with a lot of rotated objects in all orientations,
                    // and a shader with light from top/bottom
                    "mat3 tbn = mat3(finalTangent, finalBitangent, finalNormal);\n" +
                    "if(normalStrength.x > 0.0){\n" +
                    "   vec3 normalFromTex = texture(normalMap, uv).rgb * 2.0 - 1.0;\n" +
                    "        normalFromTex = tbn * normalFromTex;\n" +
                    "   finalNormal = mix(finalNormal, normalFromTex, normalStrength.x);\n" +
                    "}\n" +
                    "finalEmissive  = texture(emissiveMap, uv).rgb * emissiveBase;\n" +
                    "finalOcclusion = (1.0 - texture(occlusionMap, uv).r) * occlusionStrength;\n" +
                    "finalMetallic  = mix(metallicMinMax.x,  metallicMinMax.y,  texture(metallicMap,  uv).r);\n" +
                    "finalRoughness = mix(roughnessMinMax.x, roughnessMinMax.y, texture(roughnessMap, uv).r);\n" +

                    // reflections
                    // use roughness instead?
                    // "   if(finalMetallic > 0.0) finalColor = mix(finalColor, texture(reflectionPlane,uv).rgb, finalMetallic);\n" +
                    "if(hasReflectionPlane){\n" +
                    "   float effect = dot(reflectionPlaneNormal,finalNormal) * (1.0 - finalRoughness);\n" +
                    "   float factor = clamp((effect-.3)/.7, 0.0, 1.0);\n" +
                    "   if(factor > 0.0){\n" +
                    "       vec3 newColor = vec3(0.0);\n" +
                    "       vec3 newEmissive = finalColor * texelFetch(reflectionPlane, ivec2(gl_FragCoord.xy), 0).rgb;\n" +
                    // also multiply for mirror color <3
                    "       finalEmissive = mix(finalEmissive, newEmissive, factor);\n" +
                    // "       finalEmissive /= (1-finalEmissive);\n" + // only required, if tone mapping is applied
                    "       finalColor = mix(finalColor, newColor, factor);\n" +
                    // "       finalRoughness = 0;\n" +
                    // "       finalMetallic = 0;\n" +
                    "   }\n" +
                    "};\n" +

                    // sheen calculation
                    "vec3 V0 = normalize(-finalPosition);\n" +
                    "if(sheen > 0.0){\n" +
                    "   vec3 sheenNormal = finalNormal;\n" +
                    "   if(finalSheen * normalStrength.y > 0.0){\n" +
                    "      vec3 normalFromTex = texture(sheenNormalMap, uv).rgb * 2.0 - 1.0;\n" +
                    "           normalFromTex = tbn * normalFromTex;\n" +
                    // original or transformed "finalNormal"? mmh...
                    // transformed probably is better
                    "      sheenNormal = mix(finalNormal, normalFromTex, normalStrength.y);\n" +
                    "   }\n" +
                    // calculate sheen
                    "   float sheenFresnel = 1.0 - abs(dot(sheenNormal,V0));\n" +
                    "   finalSheen = sheen * pow(sheenFresnel, 3.0);\n" +
                    "} else finalSheen = 0.0;\n" +

                    "if(finalClearCoat.w > 0.0){\n" +
                    // cheap clear coat effect
                    "   float fresnel = 1.0 - abs(dot(finalNormal,V0));\n" +
                    "   float clearCoatEffect = pow(fresnel, 3.0) * finalClearCoat.w;\n" +
                    "   finalRoughness = mix(finalRoughness, finalClearCoatRoughMetallic.x, clearCoatEffect);\n" +
                    "   finalMetallic = mix(finalMetallic, finalClearCoatRoughMetallic.y, clearCoatEffect);\n" +
                    "   finalColor = mix(finalColor, finalClearCoat.rgb, clearCoatEffect);\n" +
                    "}\n"

        ).apply { functions.add(getColorFunc) }
    }

    init {
        glslVersion = 330
    }

    companion object {
        val rgbaShader = YTextureShader(false)
        val r16fShader = YTextureShader(true)
    }

}