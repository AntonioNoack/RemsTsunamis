package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.draw.Drawing
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import org.joml.Vector3i
import org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42C.glMemoryBarrier

open class ComputeEngine(width: Int, height: Int) :
    GPUEngine<Texture2D>(width, height, { createTexture(it, width, height) }) {

    override fun setFromTextureRGBA32F(texture: Texture2D) {
        copyTextureRGBA32F(texture, src)
    }

    override fun createBuffer(buffer: Texture2D) {
        buffer.createFP32()
    }

    override fun createBuffer(buffer: Texture2D, data: FloatArray) {
        buffer.createRGBA(data, false)
    }

    override fun destroyBuffer(buffer: Texture2D) {
        buffer.destroy()
    }

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

    override fun synchronize() {
        super.synchronize()
        GFX.checkIsGFXThread()
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        synchronizeGraphics()
    }

    override fun requestFluidTexture(cw: Int, ch: Int) = src

    companion object {

        private val scaleShader by lazy {
            ComputeShader(
                "scale-down", Vector3i(16, 16, 1), listOf(), "" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 outSize;\n" +
                        "uniform vec2 scale;\n" +
                        "void main(){\n" +
                        "   ivec2 uvOut = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uvOut.x < outSize.x && uvOut.y < outSize.y){\n" +
                        "       ivec2 uvIn = ivec2(vec2(uvOut) * scale);\n" +
                        "       imageStore(dst, uvOut, imageLoad(src, uvIn));\n" +
                        "   }\n" +
                        "}"
            )
        }

        fun scaleTextureRGBA32F(src: Texture2D, dst: Texture2D): Texture2D {
            val shader = scaleShader
            shader.use()
            shader.v2f("scale", src.width.toFloat() / dst.width.toFloat(), src.height.toFloat() / dst.height.toFloat())
            shader.v2i("outSize", dst.width, dst.height)
            shader.bindTexture(0, src, ComputeTextureMode.READ)
            shader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(dst.width, dst.height)
            GFX.check()
            return dst
        }

        fun copyTextureRGBA32F(src: Texture2D, dst: Texture2D): Texture2D {
            if (src.width != dst.width || src.height != dst.height) throw IllegalArgumentException("Textures must have same size")
            val shader = Drawing.rgbaShaders.copyShader
            shader.use()
            shader.v2i("offset", 0, 0)
            shader.v2i("inSize", src.width, src.height)
            shader.bindTexture(0, src, ComputeTextureMode.READ)
            shader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(src.width, src.height)
            GFX.check()
            return dst
        }

        fun createTexture(name: String, width: Int, height: Int): Texture2D {
            val tex = Texture2D(name, width, height, 1)
            initTexture(tex)
            return tex
        }

        private fun createShader(x: Boolean): ComputeShader {
            val p = if (x) "xyw" else "xzw"
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector3i(16, 16, 1), listOf(), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        "uniform float minFluidHeight;\n" +
                        GLSLSolver.fWaveSolverHalf +
                        "void main(){\n" +
                        "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv1.x <= maxUV.x && uv1.y <= maxUV.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec4 data0 = imageLoad(src, max(uv1 - deltaUV, ivec2(0)));\n" + // left/top
                        "       vec4 data1 = imageLoad(src, uv1);\n" +
                        "       vec4 data2 = imageLoad(src, min(uv1 + deltaUV, maxUV));\n" + // right/bottom
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
            shader.runBySize(src.width, src.height)
        }

    }

}