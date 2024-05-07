package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import org.joml.Vector3i

class ComputeYXEngine(width: Int, height: Int) :
    ComputeEngine(width, height) {

    override fun step(gravity: Float, scaling: Float, minFluidHeight: Float) {
        GFX.checkIsGFXThread()
        step(shaders.first, gravity, scaling, minFluidHeight, src, tmp)
        step(shaders.second, gravity, scaling, minFluidHeight, tmp, src)
    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {
        GFX.checkIsGFXThread()
        if (x) {
            step(shaders.first, gravity, scaling, minFluidHeight, src, tmp)
        } else {
            step(shaders.second, gravity, scaling, minFluidHeight, tmp, src)
        }
    }

    companion object {

        private fun createShader(x: Boolean): ComputeShader {
            val p = if (x) "xyw" else "xzw"
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector3i(16, 16, 1), emptyList(), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        "uniform float minFluidHeight;\n" +
                        GLSLSolver.fWaveSolverHalf +
                        "void main(){\n" +
                        "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.yx);\n" +
                        "   if(uv1.x <= maxUV.x && uv1.y <= maxUV.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec4 data0 = imageLoad(src, clamp(uv1 - deltaUV, ivec2(0), maxUV));\n" + // left/top
                        "       vec4 data1 = imageLoad(src, uv1);\n" +
                        "       vec4 data2 = imageLoad(src, clamp(uv1 + deltaUV, ivec2(0), maxUV));\n" + // right/bottom
                        "       vec2 update = timeScale * (solveZW(data0.$p, data1.$p) + solveXY(data1.$p, data2.$p));\n" + // 2 flops + 2 flops for +, plus 2 * 41 flops for calls
                        "       vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" + // 2 flops
                        "       if(newData.x < 0) newData.x = 0;\n" +
                        "       imageStore(dst, uv1, newData);\n" + // total: 88 flops
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(
            shader: ComputeShader, gravity: Float, timeScale: Float, minFluidHeight: Float,
            src: Texture2D, dst: Texture2D
        ) {
            shader.use()
            initShader(shader, timeScale, gravity, minFluidHeight, src)
            shader.bindTexture(0, src, ComputeTextureMode.READ)
            shader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(src.height, src.width)
        }

    }

}