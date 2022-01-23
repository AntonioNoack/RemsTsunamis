package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.OpenGL.useFrame
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.shader.OpenGLShader.Companion.attribute
import me.anno.gpu.shader.Renderer
import me.anno.gpu.shader.Shader
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.GPUFiltering
import me.anno.tsunamis.FluidSim
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

class GraphicsEngine(width: Int, height: Int) : GPUEngine<Framebuffer>(width, height, { name ->
    val buffer = Framebuffer(name, width, height, 1, 1, true, DepthBufferType.NONE)
    buffer.autoUpdateMipmaps = false
    buffer
}) {

    override fun createBuffer(buffer: Framebuffer, data: FloatArray) {
        buffer.ensure() // otherwise getColor() may be undefined
        buffer.getColor0().createRGBA(data, false)
        for (tex in buffer.textures) {
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }
    }

    override fun createBuffer(buffer: Framebuffer) {
        buffer.ensure()
        buffer.getColor0().createFP32()
        for (tex in buffer.textures) {
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }
    }

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        step(src, tmp, gravity, scaling)
    }

    override fun synchronize() {
        super.synchronize()
        synchronizeGraphics()
    }

    override fun createFluidTexture(w: Int, h: Int, cw: Int, ch: Int) = src.getColor0()

    override fun updateStatistics(sim: FluidSim) {

    }

    override fun destroyBuffer(buffer: Framebuffer) {
        buffer.destroy()
    }

    companion object {

        fun synchronizeGraphics() {
            GFX.checkIsGFXThread()
            GL11.glFlush()
            GL11.glFinish() // wait for everything to be drawn
            // should be enough for synchronization
        }

        private fun createShader(x: Boolean): Shader {
            val p = if (x) "xyw" else "xzw"
            val shader = Shader(
                if (x) "gfxTimeStep(x)" else "gfxTimeStep(y)",
                null, "" +
                        "$attribute vec2 attr0;\n" +
                        "void main(){ gl_Position = vec4(attr0*2.0-1.0,0.5,1.0); }",
                emptyList(), "" +
                        "uniform sampler2D state0;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverHalf +
                        // "(layout = 0) out vec4 gl_FragColor;\n" +
                        "void main(){\n" +
                        "   int lod = 0;\n" +
                        "   ivec2 uv = ivec2(gl_FragCoord.xy);\n" +
                        "   ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "   vec4 data0 = texelFetch(state0, max(uv-deltaUV, ivec2(0)), lod);\n" +
                        "   vec4 data1 = texelFetch(state0, uv, lod);\n" +
                        "   vec4 data2 = texelFetch(state0, min(uv+deltaUV, maxUV), lod);\n" +
                        "   vec2 update = timeScale * (solveZW(data0.$p, data1.$p) + solveXY(data1.$p, data2.$p));\n" + // 2 flops for multiplication + 2 flops for addition + 2 * 41 flops for call
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