package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.OpenGL.useFrame
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.shader.Renderer
import me.anno.gpu.shader.Shader
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.GPUFiltering
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.setups.FluidSimSetup
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

class GraphicsEngine(width: Int, height: Int) : CPUEngine(width, height) {

    val src = Framebuffer("tsunami-gfx", width, height, 1, 1, true, DepthBufferType.NONE)
    val tmp = Framebuffer("tsunami-gfx-tmp", width, height, 1, 1, true, DepthBufferType.NONE)
    private var maxVelocity = 0f

    override fun init(sim: FluidSim, setup: FluidSimSetup, gravity: Float) {
        GFX.checkIsGFXThread()
        super.init(sim, setup, gravity)
        maxVelocity = super.computeMaxVelocity(gravity)
        super.updateStatistics(sim)
        uploadFramebuffer()
    }

    private fun uploadFramebuffer() {
        val data = createTextureData(width, height, this)
        src.ensure() // otherwise getColor() may be undefined
        src.getColor0().createRGBA(data, false)
        tmp.ensure()
        tmp.getColor0().createRGBA(data, false)
    }

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        step(src, tmp, gravity, scaling)
    }

    override fun setZero() {
        GFX.checkIsGFXThread()
        super.setZero()
        uploadFramebuffer()
    }

    override fun synchronize() {
        super.synchronize()
        synchronizeGraphics()
    }

    override fun supportsMesh() = false

    override fun supportsTexture() = true

    override fun supportsAsyncCompute() = false

    override fun createFluidTexture(w: Int, h: Int, cw: Int, ch: Int) = src.getColor0()

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

    override fun updateStatistics(sim: FluidSim) {

    }

    override fun computeMaxVelocity(gravity: Float): Float {
        // assumed to be constant for simplicity
        return maxVelocity
    }

    override fun destroy() {
        super.destroy()
        src.destroy()
        tmp.destroy()
    }

    companion object {

        fun synchronizeGraphics() {
            GFX.checkIsGFXThread()
            GL11.glFlush()
            GL11.glFinish() // wait for everything to be drawn
            // should be enough for synchronization
        }

        private fun createShader(x: Boolean): Shader {
            val shader = Shader(
                "tsunami-gfx-sim-$x", null, ShaderLib.simplestVertexShader, ShaderLib.uvList, "" +
                        "uniform sampler2D state0;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverFull +
                        GLSLSolver.fWaveSolverHalf +
                        "void main(){\n" +
                        "   int lod = 0;\n" +
                        "   ivec2 uv = ivec2(gl_FragCoord.xy);\n" +
                        "   ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "   vec4 data0 = texelFetch(state0, max(uv-deltaUV, ivec2(0)), lod);\n" +
                        "   vec4 data1 = texelFetch(state0, uv, lod);\n" +
                        "   vec4 data2 = texelFetch(state0, min(uv+deltaUV, maxUV), lod);\n" +
                        "   vec2 update = timeScale * (${ // 2 flops for multiplication
                            if (x) "solveZW(data0.xyw, data1.xyw) + solveXY(data1.xyw, data2.xyw)" // 2 flops for addition + 2 * 41 flops for call
                            else "  solveZW(data0.xzw, data1.xzw) + solveXY(data1.xzw, data2.xzw)"
                        });\n" +
                        "   vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" + // 2 flops for addition
                        "   if(newData.x < 0) newData.x = 0;\n" +
                        "   gl_FragColor = newData;\n" + // total: 88 flops
                        "}"
            )
            shader.setTextureIndices(listOf("state0"))
            return shader
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(shader: Shader, src: Framebuffer, dst: Framebuffer, gravity: Float, timeScale: Float) {
            useFrame(dst, Renderer.copyRenderer) {
                shader.use()
                shader.v1f("gravity", gravity)
                shader.v1f("timeScale", timeScale)
                GL20.glUniform2i(shader.getUniformLocation("maxUV"), src.w - 1, src.h - 1)
                src.bindTexture0(0, GPUFiltering.TRULY_NEAREST, Clamping.CLAMP)
                GFX.flat01.draw(shader)
            }
        }

        fun step(src: Framebuffer, tmp: Framebuffer, gravity: Float, timeScale: Float) {
            renderPurely {
                step(shaders.first, src, tmp, gravity, timeScale)
                step(shaders.second, tmp, src, gravity, timeScale)
            }
        }

    }

}