package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.setups.FluidSimSetup
import org.joml.Vector2i

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

        private fun createShader(x: Boolean): ComputeShader {
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector2i(8, 8), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform vec2 textureSize;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverFull +
                        GLSLSolver.fWaveSolverHalf +
                        "void main(){\n" +
                        "   if(gl_GlobalInvocationID.x < textureSize.x && gl_GlobalInvocationID.y < textureSize.y){\n" +
                        "       ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "       ivec2 uv0 = clamp(uv1 - ${if (x) "ivec2(1,0)" else "ivec2(0,1)"}, ivec2(0), ivec2(textureSize));\n" +
                        "       ivec2 uv2 = clamp(uv1 + ${if (x) "ivec2(1,0)" else "ivec2(0,1)"}, ivec2(0), ivec2(textureSize));\n" +
                        "       vec4 data0 = imageLoad(src, uv0);\n" +
                        "       vec4 data1 = imageLoad(src, uv1);\n" +
                        "       vec4 data2 = imageLoad(src, uv2);\n" +
                        "       vec2 update = timeScale * (${
                            if (x) "solveZW(data0.xyw, data1.xyw) + solveXY(data1.xyw, data2.xyw)"
                            else "  solveZW(data0.xzw, data1.xzw) + solveXY(data1.xzw, data2.xzw)"
                        });\n" +
                        "       vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" +
                        "       if(newData.x < 0) newData.x = 0;\n" +
                        "       imageStore(dst, uv1, newData);\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(shader: ComputeShader, gravity: Float, timeScale: Float, src: Texture2D, dst: Texture2D) {
            shader.use()
            shader.v2("textureSize", src.w.toFloat(), src.h.toFloat())
            shader.v1("timeScale", timeScale)
            shader.v1("gravity", gravity)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, dst, ComputeTextureMode.WRITE)
        }

        fun step(gravity: Float, timeScale: Float, src: Texture2D, tmp: Texture2D) {
            renderPurely {
                step(shaders.first, gravity, timeScale, src, tmp)
                step(shaders.second, gravity, timeScale, tmp, src)
            }
        }


    }

}