package me.anno.tsunamis

import me.anno.engine.ui.render.ECSMeshShader
import me.anno.engine.ui.render.Renderers
import me.anno.gpu.deferred.DeferredSettingsV2
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.GeoShader
import me.anno.gpu.shader.Shader
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.ShaderStage
import me.anno.gpu.shader.builder.Variable

object YTextureShader : ECSMeshShader("YTexture") {

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
        list.add(Variable(GLSLType.S2D, "fluidData"))
        list.add(Variable(GLSLType.S2D, "colorMap"))
        list.add(Variable(GLSLType.V2F, "colorMapScale"))
        list.add(Variable(GLSLType.V1F, "cellSize"))
        list.add(Variable(GLSLType.V3F, "cellOffset"))
        list.add(Variable(GLSLType.V1F, "visScale"))
        list.add(Variable(GLSLType.V1I, "visualization"))
        return list
    }

    override fun createVertexStage(instanced: Boolean, colors: Boolean): ShaderStage {

        val defines = "" +
                (if (instanced) "#define INSTANCED\n" else "") +
                (if (colors) "#define COLORS\n" else "")

        glslVersion = 330

        return ShaderStage(
            "vertex",
            createVertexAttributes(instanced, colors),
            "" +
                    defines +
                    // create x,z coordinates from vertex index
                    // create y,normal from texture data
                    "int lod = 0;\n" +
                    "#define SURFACE(h) (h.r + h.a)\n" +
                    "#define TEXTURE fluidData\n" +
                    "#define getColor11(v) v < 0.0 ? mix(vec3(0.0,0.33,1.0), vec3(1.0), fract(v)) : mix(vec3(1.0), vec3(1.0,0.0,0.0), v)\n" +
                    "ivec2 fieldSize = textureSize(TEXTURE, lod);\n" +
                    "int cellIndex  = gl_VertexID / 6;\n" +
                    "int partOfCell = gl_VertexID % 6;\n" +
                    "int deltaX[6] = { 0, 1, 1, 1, 0, 0 };\n" +
                    "int deltaY[6] = { 0, 0, 1, 1, 0, 1 };\n" +
                    "int numCellsX = max(1, fieldSize.x - 1);\n" +
                    "int cellX = cellIndex % numCellsX + deltaX[partOfCell];\n" +
                    "int cellY = cellIndex / numCellsX + deltaY[partOfCell];\n" +
                    "ivec2 cell = ivec2(cellX, cellY);\n" +
                    "vec2 invFieldSize = 1.0 / vec2(fieldSize-1);\n" +
                    "vec2 uv = vec2(cellX, cellY) * invFieldSize;\n" + // [0,1]
                    "vec4 data = texelFetch(TEXTURE, cell, lod);\n" +
                    "localPosition = vec3(float(cellX) * cellSize, SURFACE(data), float(cellY) * cellSize) - cellOffset;\n" +
                    "#ifdef COLORS\n" +
                    // calculate the normals
                    "   vec4 dxp = texelFetch(TEXTURE, cell + ivec2(1, 0), lod);\n" +
                    "   vec4 dxm = texelFetch(TEXTURE, cell - ivec2(1, 0), lod);\n" +
                    "   vec4 dyp = texelFetch(TEXTURE, cell + ivec2(0, 1), lod);\n" +
                    "   vec4 dym = texelFetch(TEXTURE, cell - ivec2(0, 1), lod);\n" +
                    "   vec3 normals = normalize(vec3(\n" +
                    "       SURFACE(dxp) - SURFACE(dxm),\n" +
                    "       cellSize * 2.0,\n" +
                    "       SURFACE(dyp) - SURFACE(dym)\n" +
                    "   ));\n" +
                    "   vec3 tangents = vec3(1.0, 0.0, 0.0);\n" + // should be done more properly
                    "#endif\n" +
                    "#ifdef INSTANCED\n" +
                    "   mat4x3 localTransform = mat4x3(instanceTrans0, instanceTrans1, instanceTrans2);\n" +
                    "   finalPosition = localTransform * vec4(localPosition, 1.0);\n" +
                    "   #ifdef COLORS\n" +
                    "       normal = localTransform * vec4(normals, 0.0);\n" +
                    "       tangent = localTransform * vec4(tangents, 0.0);\n" +
                    "       tint = instanceTint;\n" +
                    "   #endif\n" +
                    "#else\n" +
                    "   #ifdef COLORS\n" +
                    "       normal = normals;\n" +
                    "       tangent = tangents;\n" +
                    "   #endif\n" +
                    "   finalPosition = localTransform * vec4(localPosition, 1.0);\n" +
                    "   #ifdef COLORS\n" +
                    "       normal = localTransform * vec4(normal, 0.0);\n" +
                    "       tangent = localTransform * vec4(tangent, 0.0);\n" +
                    "   #endif\n" +
                    "#endif\n" +
                    "#ifdef COLORS\n" +
                    "   normal = normalize(normal);\n" +
                    "   float v = 0;\n" +
                    "   vertexColor = texture(colorMap, vec2(data.a * colorMapScale.x + colorMapScale.y, 0.0));\n" +
                    "   if(data.a < 0.0 && visualization != ${Visualisation.HEIGHT_MAP.id}){\n" +
                    "       switch(visualization){\n" +
                    "       case ${Visualisation.WATER_SURFACE.id}:\n" +
                    "           v = (data.r + data.a) * visScale;break;\n" +
                    "       case ${Visualisation.MOMENTUM_X.id}:\n" +
                    "           v = data.g * visScale;break;\n" +
                    "       case ${Visualisation.MOMENTUM_Y.id}:\n" +
                    "           v = data.b * visScale;break;\n" +
                    "       case ${Visualisation.MOMENTUM.id}:\n" +
                    "           v = length(vec2(data.gb)) * visScale;break;\n" +
                    "       }\n" +
                    "       vertexColor = vec4(getColor11(v), 1.0);\n" +
                    "   }\n" +
                    "   uv = vec2(0.0);\n" +
                    "#endif\n" +
                    "gl_Position = transform * vec4(finalPosition, 1.0);\n" +
                    ShaderLib.positionPostProcessing
        )
    }

    init {
        glslVersion = 330
    }

}