package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.GFXState.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.maths.Maths.ceilDiv
import org.joml.Vector2i
import org.joml.Vector3i

class SharedMemoryEngine(width: Int, height: Int) :
    ComputeEngine(width, height) {

    override fun step(gravity: Float, scaling: Float, minFluidHeight: Float) {
        GFX.checkIsGFXThread()
        renderPurely {
            step(shaders.first, true, gravity, scaling, minFluidHeight, src, tmp)
            step(shaders.second, false, gravity, scaling, minFluidHeight, tmp, src)
        }
    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {
        renderPurely {
            if (x) {
                step(shaders.first, true, gravity, scaling, minFluidHeight, src, tmp)
            } else {
                step(shaders.second, false, gravity, scaling, minFluidHeight, tmp, src)
            }
        }
    }

    companion object {

        private val updateSize = 16
        private val writeSize = updateSize - 1

        private fun createShader(x: Boolean): ComputeShader {
            val p = if (x) "xyw" else "xzw"
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector3i(updateSize, updateSize, 1), listOf(), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        "uniform float minFluidHeight;\n" +
                        GLSLSolver.fWaveSolverFull +
                        "shared vec4 updates[$updateSize * $updateSize];\n" +
                        "void main(){\n" +
                        "   ivec2 localUV = ivec2(gl_LocalInvocationID.xy);\n" +
                        "   ivec2 uv1 = localUV + ivec2(gl_WorkGroupID.xy) * ${
                            if (x) "ivec2($writeSize, $updateSize)"
                            else "ivec2($updateSize, $writeSize)"
                        };\n" +
                        // compute all updates inside the group
                        "   ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "   vec4 data0 = imageLoad(src, clamp(uv1 - deltaUV, ivec2(0), maxUV));\n" + // left/top
                        "   vec4 data1 = imageLoad(src, clamp(uv1,           ivec2(0), maxUV));\n" +
                        "   int index0 = ${if (x) "localUV.x * $updateSize + localUV.y" else "localUV.x + localUV.y * $updateSize"};\n" +
                        "   updates[index0] = timeScale * solve(data0.$p, data1.$p);\n" +
                        "   memoryBarrierShared();\n" + // synchronize all members
                        "   if(uv1.x <= maxUV.x && uv1.y <= maxUV.y && localUV.${if (x) "x" else "y"} < $writeSize){\n" +
                        // read the relevant updates from the group members
                        "       vec2 update = updates[index0].zw + updates[index0 + $updateSize].xy;\n" +
                        "       vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" +
                        "       if(newData.x < 0) newData.x = 0;\n" +
                        "       imageStore(dst, uv1, newData);\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(
            shader: ComputeShader,
            x: Boolean,
            gravity: Float,
            timeScale: Float,
            minFluidHeight: Float,
            src: Texture2D,
            dst: Texture2D
        ) {
            shader.use()
            initShader(shader, timeScale, gravity, minFluidHeight, src)
            shader.bindTexture(0, src, ComputeTextureMode.READ)
            shader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            if (x) {
                // we need extra groups on the x-axis
                shader.runBySize(ceilDiv(src.width * updateSize, writeSize), src.height)
            } else {
                // extra groups, but for the y-axis
                shader.runBySize(src.width, ceilDiv(src.height * updateSize, writeSize))
            }
        }

    }

}