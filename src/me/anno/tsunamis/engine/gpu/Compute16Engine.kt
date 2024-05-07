package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.TargetType
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.TsunamiEngine
import me.anno.tsunamis.engine.gpu.Compute16Shaders.Companion.b16Shaders
import me.anno.tsunamis.engine.gpu.Compute16Shaders.Companion.b32Shaders
import me.anno.tsunamis.engine.gpu.GPUEngine.Companion.initShader
import me.anno.tsunamis.engine.gpu.GPUEngine.Companion.initTexture
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import me.anno.tsunamis.setups.FluidSimSetup
import org.lwjgl.opengl.GL30C.*

/**
 * this is an engine, which uses fp16 values instead of fp32, and saves the surface height instead of the fluid height;
 * the hope of this experiment was to minimize data transfers
 * */
class Compute16Engine(
    width: Int, height: Int,
    val bathymetryFp16: Boolean = true
) : CPUEngine(width, height) {

    // todo fix bug: when switching between their two types, sometimes the engine resets the progress... why?

    private val shaders = if (bathymetryFp16) b16Shaders else b32Shaders

    override fun isCompatible(engine: TsunamiEngine): Boolean {
        return super.isCompatible(engine) && engine is Compute16Engine &&
                engine.bathymetryFp16 == bathymetryFp16
    }

    /**
     * two cycles:
     * s0,hx0,bath -> s1,hx1,bath
     * s1,hy0,bath -> s0,hy1,bath
     * s0,hx1,bath -> s1,hx0,bath
     * s1,hy1,bath -> s0,hy0,bath
     * */

    val bathymetryTex = Texture2D("bath", width, height, 1)

    val surface0 = Texture2D("s16-0", width, height, 1)
    val surface1 = Texture2D("s16-1", width, height, 1)

    private val momentumX0 = Texture2D("mx16-0", width, height, 1)
    private val momentumX1 = Texture2D("mx16-1", width, height, 1)
    private val momentumY0 = Texture2D("my16-0", width, height, 1)
    private val momentumY1 = Texture2D("my16-1", width, height, 1)

    val momentumX get() = if (isEvenIteration) momentumX0 else momentumX1
    val momentumY get() = if (isEvenIteration) momentumY0 else momentumY1

    // can be replaced, if we adjust our render shader
    private val rendered = Texture2D("rend16", width, height, 1)

    private var isEvenIteration = true
    private var hasChanged = false

    private var maxVelocity = 0f

    fun invalidate() {
        hasChanged = true
    }

    override fun supportsAsyncCompute(): Boolean = false
    override fun supportsMesh(): Boolean = false
    override fun supportsTexture(): Boolean = true
    override fun computeMaxVelocity(gravity: Float, minFluidHeight: Float): Float = maxVelocity

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float, minFluidHeight: Float) {

        super.init(sim, setup, gravity, minFluidHeight)
        maxVelocity = super.computeMaxVelocity(gravity, minFluidHeight)
        if (sim != null) super.updateStatistics(sim)

        val buffer = fbPool[width * height * 4, false, false]
        val data = GLSLSolver.createTextureData(width, height, this, buffer)
        initTexture(rendered)
        rendered.createRGBA(data, false)
        fbPool.returnBuffer(buffer)

        for (tex in listOf(
            surface0,
            surface1,
            momentumX0,
            momentumX1,
            momentumY0,
            momentumY1
        )) {
            initTexture(tex)
            tex.create(FP16x1)
        }

        initTexture(bathymetryTex)
        bathymetryTex.create(
            if (bathymetryFp16) FP16x1
            else TargetType.Float32x1
        )

        setFromTextureRGBA32F(rendered)

    }

    override fun setFromTextureRGBA32F(texture: Texture2D) {
        val shader = shaders.splitShader
        shader.use()
        shader.v2i("maxUV", width - 1, height - 1)
        // bind all textures
        shader.bindTexture(0, texture, ComputeTextureMode.READ, GL_RGBA32F)
        shader.bindTexture(1, surface0, ComputeTextureMode.WRITE, GL_R16F)
        shader.bindTexture(2, momentumX0, ComputeTextureMode.WRITE, GL_R16F)
        shader.bindTexture(3, momentumY0, ComputeTextureMode.WRITE, GL_R16F)
        shader.bindTexture(4, bathymetryTex, ComputeTextureMode.WRITE, if (bathymetryFp16) GL_R16F else GL_R32F)
        shader.runBySize(width, height)
        isEvenIteration = true
        hasChanged = true
    }

    override fun destroy() {
        super.destroy()
        bathymetryTex.destroy()
        surface0.destroy()
        surface1.destroy()
        momentumX0.destroy()
        momentumX1.destroy()
        momentumY0.destroy()
        momentumY1.destroy()
        rendered.destroy()
    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {
        val shaders = shaders.updateShaders
        if (x) {
            step(
                shaders.first,
                surface0, if (isEvenIteration) momentumX0 else momentumX1,
                surface1, if (isEvenIteration) momentumX1 else momentumX0,
                bathymetryTex, bathymetryFp16, gravity, scaling, minFluidHeight
            )
        } else {
            step(
                shaders.second,
                surface1, if (isEvenIteration) momentumY0 else momentumY1,
                surface0, if (isEvenIteration) momentumY1 else momentumY0,
                bathymetryTex, bathymetryFp16, gravity, scaling, minFluidHeight
            )
            isEvenIteration = !isEvenIteration
        }
        hasChanged = true
    }

    override fun synchronize() {
        super.synchronize()
        synchronizeGraphics()
    }

    override fun requestFluidTexture(cw: Int, ch: Int): Texture2D {
        GFX.checkIsGFXThread()
        // this step could reduce the resolution for faster rendering
        // (but that probably is not necessary, as a slow pc will have trouble computing the sim as well)
        if (hasChanged) {
            // render partial data into rendered
            val shader = shaders.mergeShader
            shader.use()
            shader.v2i("maxUV", width - 1, height - 1)
            // bind all textures
            shader.bindTexture(0, rendered, ComputeTextureMode.WRITE)
            shader.bindTexture(1, surface0, ComputeTextureMode.READ, GL_R16F)
            shader.bindTexture(2, if (isEvenIteration) momentumX0 else momentumX1, ComputeTextureMode.READ, GL_R16F)
            shader.bindTexture(3, if (isEvenIteration) momentumY0 else momentumY1, ComputeTextureMode.READ, GL_R16F)
            shader.bindTexture(4, bathymetryTex, ComputeTextureMode.READ, if (bathymetryFp16) GL_R16F else GL_R32F)
            shader.runBySize(width, height)
            hasChanged = false
        }
        return rendered
    }

    override fun updateStatistics(sim: FluidSim) {
        // not supported for gpu, because it would be relatively expensive
        // could be implemented in the future, and be enabled/disabled
    }

    companion object {

        private val FP16x1 = TargetType("fp16x1", GL_R16F, GL_RED, GL_HALF_FLOAT, 2, 1, true)

        private fun step(
            shader: ComputeShader,
            srcSurface: Texture2D,
            srcMomentum: Texture2D,
            dstSurface: Texture2D,
            dstMomentum: Texture2D,
            bathymetry: Texture2D,
            bathymetryHalf: Boolean,
            gravity: Float,
            timeScale: Float,
            minFluidHeight: Float
        ) {
            shader.use()
            initShader(shader, timeScale, gravity, minFluidHeight, srcSurface)
            shader.bindTexture(0, srcSurface, ComputeTextureMode.READ, GL_R16F)
            shader.bindTexture(1, srcMomentum, ComputeTextureMode.READ, GL_R16F)
            shader.bindTexture(2, dstSurface, ComputeTextureMode.WRITE, GL_R16F)
            shader.bindTexture(3, dstMomentum, ComputeTextureMode.WRITE, GL_R16F)
            shader.bindTexture(4, bathymetry, ComputeTextureMode.READ, if (bathymetryHalf) GL_R16F else GL_R32F)
            shader.runBySize(srcSurface.width, srcSurface.height)
        }

    }

}