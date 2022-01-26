package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.GPUFiltering
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

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float) {
        GFX.checkIsGFXThread()
        super.init(sim, setup, gravity)
        maxVelocity = super.computeMaxVelocity(gravity)
        if (sim != null) super.updateStatistics(sim)
        uploadMainBuffer()
        createBuffer(tmp)
    }

    private fun uploadMainBuffer() {
        val buffer = fbPool[width * height * 4, false]
        val data = createTextureData(width, height, this, buffer)
        createBuffer(src, data)
        fbPool.returnBuffer(buffer)
    }

    abstract override fun step(gravity: Float, scaling: Float)

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
        /*val reduced = Reduction.reduce(src, Reduction.MAX_RA)
        sim.maxSurfaceHeight = reduced.x
        sim.maxMomentumX = reduced.x
        sim.maxMomentumY = reduced.y*/
    }

    override fun computeMaxVelocity(gravity: Float): Float {
        return maxVelocity
    }

    override fun destroy() {
        super.destroy()
        destroyBuffer(src)
        destroyBuffer(tmp)
    }

    companion object {
        fun initTextures(fb: Framebuffer) {
            fb.autoUpdateMipmaps = false
            fb.ensure()
            for (tex in fb.textures) {
                initTexture(tex)
            }
        }

        fun initTexture(tex: Texture2D) {
            tex.autoUpdateMipmaps = false
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
        }
    }
}