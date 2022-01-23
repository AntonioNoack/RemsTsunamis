package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.GPUFiltering
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import org.joml.Vector2i
import org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42C.glMemoryBarrier

class ComputeYXEngine(width: Int, height: Int) :
    ComputeEngine(width, height) {

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        step(shaders.first, gravity, scaling, src, tmp)
        step(shaders.second, gravity, scaling, tmp, src)
    }

    override fun halfStep(gravity: Float, scaling: Float, x: Boolean) {
        GFX.checkIsGFXThread()
        if (x) {
            step(shaders.first, gravity, scaling, src, tmp)
        } else {
            step(shaders.second, gravity, scaling, tmp, src)
        }
    }

    companion object {

        private fun createShader(x: Boolean): ComputeShader {
            val p = if (x) "xyw" else "xzw"
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector2i(16), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
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

        private fun step(shader: ComputeShader, gravity: Float, timeScale: Float, src: Texture2D, dst: Texture2D) {
            shader.use()
            shader.v1f("timeScale", timeScale)
            shader.v1f("gravity", gravity)
            shader.v2i("maxUV", src.w - 1, src.h - 1)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(src.h, src.w)
        }

    }

}