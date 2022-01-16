package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import me.anno.tsunamis.setups.FluidSimSetup
import org.joml.Vector2i
import org.lwjgl.opengl.GL20.glUniform2i
import org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42C.glMemoryBarrier

class ComputeEngine(width: Int, height: Int) : CPUEngine(width, height) {

    private val src = Texture2D("src", width, height, 1)
    private val tmp = Texture2D("tmp", width, height, 1)
    private var maxVelocity = 0f

    override fun init(sim: FluidSim, setup: FluidSimSetup, gravity: Float) {
        GFX.checkIsGFXThread()
        super.init(sim, setup, gravity)
        maxVelocity = super.computeMaxVelocity(gravity)
        super.updateStatistics(sim)
        uploadFramebuffer()
        tmp.createFP32()
    }

    private fun uploadFramebuffer() {
        val data = createTextureData(width, height, this)
        src.createRGBA(data, false)
    }

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        step(gravity, scaling, src, tmp)
    }

    override fun setZero() {
        GFX.checkIsGFXThread()
        super.setZero()
        uploadFramebuffer()
    }

    override fun synchronize() {
        super.synchronize()
        GFX.checkIsGFXThread()
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        synchronizeGraphics()
    }

    override fun supportsAsyncCompute() = false

    override fun supportsMesh() = false

    override fun supportsTexture() = true

    override fun createFluidTexture(w: Int, h: Int, cw: Int, ch: Int) = src

    override fun updateStatistics(sim: FluidSim) {
        // leave them be
    }

    override fun getFluidHeightAt(x: Int, y: Int): Float {
        GFX.checkIsGFXThread()
        TODO("Not yet implemented")
    }

    override fun getMomentumXAt(x: Int, y: Int): Float {
        GFX.checkIsGFXThread()
        TODO("Not yet implemented")
    }

    override fun getMomentumYAt(x: Int, y: Int): Float {
        GFX.checkIsGFXThread()
        TODO("Not yet implemented")
    }

    override fun computeMaxVelocity(gravity: Float): Float {
        return maxVelocity
    }

    override fun destroy() {
        super.destroy()
        src.destroy()
        tmp.destroy()
    }

    companion object {

        // todo unit of performance: lattice updates per second

        private fun createShader(x: Boolean): ComputeShader {
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector2i(8, 8), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverFull +
                        GLSLSolver.fWaveSolverHalf +
                        "void main(){\n" +
                        "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv1.x <= maxUV.x && uv1.y <= maxUV.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec4 data0 = imageLoad(src, clamp(uv1 - deltaUV, ivec2(0), maxUV));\n" + // left/top
                        "       vec4 data1 = imageLoad(src, uv1);\n" +
                        "       vec4 data2 = imageLoad(src, clamp(uv1 + deltaUV, ivec2(0), maxUV));\n" + // right/bottom
                        "       vec2 update = timeScale * (${// 2 flops
                            if (x) "solveZW(data0.xyw, data1.xyw) + solveXY(data1.xyw, data2.xyw)" // 2 flops for +, plus 2 * 41 flops for calls
                            else "  solveZW(data0.xzw, data1.xzw) + solveXY(data1.xzw, data2.xzw)"
                        });\n" +
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
            glUniform2i(shader.getUniformLocation("maxUV"), src.w - 1, src.h - 1)
            shader.v1("timeScale", timeScale)
            shader.v1("gravity", gravity)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(src.w, src.h)
        }

        fun step(gravity: Float, timeScale: Float, src: Texture2D, tmp: Texture2D) {
            renderPurely {
                step(shaders.first, gravity, timeScale, src, tmp)
                step(shaders.second, gravity, timeScale, tmp, src)
            }
        }


    }

}