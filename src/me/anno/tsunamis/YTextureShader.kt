package me.anno.tsunamis

import me.anno.engine.ui.render.ECSMeshShader
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.ShaderStage
import me.anno.gpu.shader.builder.Variable
import me.anno.gpu.shader.builder.VariableMode

object YTextureShader : ECSMeshShader("YTexture") {

    override fun createVertexAttributes(instanced: Boolean, colors: Boolean): ArrayList<Variable> {
        val list = super.createVertexAttributes(instanced, colors)
        list.removeIf { it.name == "coords" || it.name == "normals" || it.name == "tangents" || it.name == "uvs" }
        list.add(Variable("sampler2D", "fluidData", VariableMode.IN))
        list.add(Variable("float", "cellSize", VariableMode.IN))
        return list
    }

    override fun createVertexStage(instanced: Boolean, colors: Boolean): ShaderStage {

        val defines = "" +
                (if (instanced) "#define INSTANCED\n" else "") +
                (if (colors) "#define COLORS\n" else "")

        return ShaderStage(
            "vertex",
            createVertexAttributes(instanced, colors),
            "" +
                    defines +
                    // create x,z coordinates from vertex index
                    // create y,normal from texture data
                    "ivec2 fieldSize = textureSize(fluidData);\n" +
                    "float cellSize = 50.0;\n" +
                    "int cellIndex  = gl_VertexID / 6;\n" +
                    "int partOfCell = gl_VertexID % 6;\n" +
                    "int deltaX[6] = { 0, 1, 1, 1, 0, 0 };\n" +
                    "int deltaY[6] = { 0, 0, 1, 1, 0, 1 };\n" +
                    "int numCellsX = fieldSize.x - 1;\n" +
                    "int cellX = cellIndex % numCellsX + deltaX[partOfCell];\n" +
                    "int cellY = cellIndex / numCellsX + deltaY[partOfCell];\n" +
                    "ivec2 cell = ivec2(cellX, cellY);\n" +
                    "vec2 invFieldSize = 1.0 / vec2(fieldSize-1);\n" +
                    "vec2 uv = vec2(cellX, cellY) * invFieldSize;\n" + // [0,1]
                    // todo center mesh
                    "vec3 coords = vec3(float(cellX) * cellSize, texelFetch(fluidData, cell, 0).r, float(cellY) * cellSize);\n" +
                    "#idef COLORS\n" +
                    // calculate the normals
                    "   vec3 normals = normalize(\n" +
                    "       texelFetch(fluidData, cell + ivec2(1, 0), 0).r - texelFetch(fluidData, uv - ivec2(1, 0), 0).r,\n" +
                    "       cellSize * 2.0,\n" +
                    "       texelFetch(fluidData, cell + ivec2(0, 1), 0).r - texelFetch(fluidData, uv - ivec2(0, 1), 0).r\n" +
                    "   );\n" +
                    "   vec3 tangents = normalize(1.0, 0.0, 0.0);\n" + // should be done more properly
                    "#endif\n" +
                    "#ifdef INSTANCED\n" +
                    "   mat4x3 localTransform = mat4x3(instanceTrans0,instanceTrans1,instanceTrans2);\n" +
                    "   finalPosition = localTransform * vec4(coords, 1.0);\n" +
                    "   #ifdef COLORS\n" +
                    "       normal = localTransform * vec4(normals, 0.0);\n" +
                    "       tangent = localTransform * vec4(tangents, 0.0);\n" +
                    "       tint = instanceTint;\n" +
                    "   #endif\n" +
                    "#else\n" +
                    "   if(hasAnimation){\n" +
                    "       mat4x3 jointMat;\n" +
                    "       jointMat  = jointTransforms[indices.x] * weights.x;\n" +
                    "       jointMat += jointTransforms[indices.y] * weights.y;\n" +
                    "       jointMat += jointTransforms[indices.z] * weights.z;\n" +
                    "       jointMat += jointTransforms[indices.w] * weights.w;\n" +
                    "       localPosition = jointMat * vec4(coords, 1.0);\n" +
                    "       #ifdef COLORS\n" +
                    "           normal = jointMat * vec4(normals, 0.0);\n" +
                    "           tangent = jointMat * vec4(tangents, 0.0);\n" +
                    "       #endif\n" +
                    "   } else {\n" +
                    "       localPosition = coords;\n" +
                    "       #ifdef COLORS\n" +
                    "           normal = normals;\n" +
                    "           tangent = tangents;\n" +
                    "       #endif\n" +
                    "   }\n" +
                    "   finalPosition = localTransform * vec4(localPosition, 1.0);\n" +
                    "   #ifdef COLORS\n" +
                    "       normal = localTransform * vec4(normal, 0.0);\n" +
                    "       tangent = localTransform * vec4(tangent, 0.0);\n" +
                    "   #endif" +
                    "#endif\n" +
                    "#ifdef COLORS\n" +
                    "   normal = normalize(normal);\n" +
                    "   vertexColor = vec4(1.0);\n" +
                    "   uv = vec2(0.0);\n" +
                    "#endif\n" +
                    "gl_Position = transform * vec4(finalPosition, 1.0);\n" +
                    ShaderLib.positionPostProcessing
        )
    }

}