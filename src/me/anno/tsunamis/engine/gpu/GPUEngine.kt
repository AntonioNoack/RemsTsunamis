package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.shader.GPUShader
import me.anno.gpu.shader.Reduction
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.Filtering
import me.anno.gpu.texture.ITexture2D
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.setups.FluidSimSetup

abstract class GPUEngine<Buffer>(
    width: Int, height: Int,
    val src: Buffer,
    val tmp: Buffer
) : CPUEngine(width, height) {

    constructor(width: Int, height: Int, generator: (name: String) -> Buffer) :
            this(width, height, generator("src"), generator("tmp"))

    abstract fun createBuffer(buffer: Buffer)
    abstract fun createBuffer(buffer: Buffer, data: FloatArray)

    abstract fun destroyBuffer(buffer: Buffer)

    private var maxVelocity = 0f

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float, minFluidHeight: Float) {
        GFX.checkIsGFXThread()
        super.init(sim, setup, gravity, minFluidHeight)
        maxVelocity = super.computeMaxVelocity(gravity, minFluidHeight)
        if (sim != null) super.updateStatistics(sim)
        uploadMainBuffer()
        createBuffer(tmp)
    }

    private fun uploadMainBuffer() {
        val buffer = fbPool[width * height * 4, false, false]
        val data = createTextureData(width, height, this, buffer)
        createBuffer(src, data)
        fbPool.returnBuffer(buffer)
    }

    abstract override fun step(gravity: Float, scaling: Float, minFluidHeight: Float)

    override fun setZero() {
        GFX.checkIsGFXThread()
        super.setZero()
        uploadMainBuffer()
    }

    override fun supportsAsyncCompute() = false

    override fun supportsMesh() = false

    override fun supportsTexture() = true

    override fun updateStatistics(sim: FluidSim) {
        // leave them be
        if (src is ITexture2D) {
            val reduced = Reduction.reduce(src, MAX_RA)
            sim.maxSurfaceHeight = reduced.x
            sim.maxMomentumX = reduced.y
            sim.maxMomentumY = reduced.z
        }
    }

    override fun computeMaxVelocity(gravity: Float, minFluidHeight: Float): Float {
        return maxVelocity
    }

    override fun destroy() {
        super.destroy()
        destroyBuffer(src)
        destroyBuffer(tmp)
    }

    companion object {
        fun initTextures(fb: Framebuffer) {
            fb.ensure()
            for (tex in fb.textures ?: emptyList()) {
                initTexture(tex)
            }
        }

        fun initTexture(tex: Texture2D) {
            tex.filtering = Filtering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }

        fun initShader(shader: GPUShader, timeScale: Float, gravity: Float, minFluidHeight: Float, src: ITexture2D) {
            shader.v1f("timeScale", timeScale)
            shader.v1f("gravity", gravity)
            shader.v1f("minFluidHeight", minFluidHeight)
            shader.v2i("maxUV", src.width - 1, src.height - 1)
        }
    }
}