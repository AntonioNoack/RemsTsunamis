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
import me.anno.gpu.texture.ITexture2D
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import org.lwjgl.opengl.GL11

class GraphicsEngine(width: Int, height: Int) : GPUEngine<Framebuffer>(width, height, { name ->
    val buffer = Framebuffer(name, width, height, 1, 1, true, DepthBufferType.NONE)
    initTextures(buffer)
    buffer
}) {

    override fun createBuffer(buffer: Framebuffer, data: FloatArray) {
        buffer.ensure() // otherwise getColor() may be undefined
        (buffer.getTexture0() as Texture2D).createRGBA(data, false)
        for (tex in buffer.textures) {
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }
    }

    override fun createBuffer(buffer: Framebuffer) {
        buffer.ensure()
        (buffer.getTexture0() as Texture2D).createFP32()
        for (tex in buffer.textures) {
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }
    }

    override fun setFromTextureRGBA32F(texture: Texture2D) {
        ComputeEngine.copyTextureRGBA32F(texture, this.src.getTexture0() as Texture2D)
    }

    override fun step(gravity: Float, scaling: Float, minFluidHeight: Float) {
        GFX.checkIsGFXThread()
        renderPurely {
            step(shaders.first, src, tmp, gravity, scaling, minFluidHeight)
            step(shaders.second, tmp, src, gravity, scaling, minFluidHeight)
        }
    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {
        renderPurely {
            if (x) {
                step(shaders.first, src, tmp, gravity, scaling, minFluidHeight)
            } else {
                step(shaders.second, tmp, src, gravity, scaling, minFluidHeight)
            }
        }
    }

    override fun synchronize() {
        super.synchronize()
        synchronizeGraphics()
    }

    override fun requestFluidTexture(cw: Int, ch: Int) = src.getTexture0() as Texture2D

    override fun updateStatistics(sim: FluidSim) {}

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
                "" +
                        "$attribute vec2 attr0;\n" +
                        "void main(){ gl_Position = vec4(attr0*2.0-1.0,0.5,1.0); }",
                emptyList(), "" +
                        "precision highp float;\n" +
                        "uniform sampler2D state0;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        "uniform float minFluidHeight;\n" +
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

        private fun step(
            shader: Shader, src: Framebuffer, dst: Framebuffer,
            gravity: Float, timeScale: Float, minFluidHeight: Float
        ) = step(shader, src.getTexture0(), dst, gravity, timeScale, minFluidHeight)

        private fun step(
            shader: Shader, src: ITexture2D, dst: Framebuffer,
            gravity: Float, timeScale: Float, minFluidHeight: Float
        ) {
            useFrame(dst, Renderer.copyRenderer) {
                shader.use()
                initShader(shader, timeScale, gravity, minFluidHeight, src)
                src.bind(0, GPUFiltering.TRULY_NEAREST, Clamping.CLAMP)
                GFX.flat01.draw(shader)
            }
        }

    }

}