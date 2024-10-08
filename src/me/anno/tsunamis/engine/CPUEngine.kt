package me.anno.tsunamis.engine

import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.engine.serialization.NotSerializedProperty
import me.anno.gpu.GFX
import me.anno.gpu.texture.Texture2D
import me.anno.gpu.texture.Texture2D.Companion.setReadAlignment
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.FluidSim.Companion.threadPool
import me.anno.tsunamis.Visualisation
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.utils.pooling.FloatArrayPool
import org.apache.logging.log4j.LogManager
import org.lwjgl.opengl.GL11C.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

open class CPUEngine(width: Int, height: Int) : TsunamiEngine(width, height) {

    @NotSerializedProperty
    val fluidTexture = Texture2D("Tsunami", width, height, 1)

    @NotSerializedProperty
    var fluidHeight: FloatArray = FluidSim.f0

    @NotSerializedProperty
    private var tmpH = FluidSim.f0

    @NotSerializedProperty
    var fluidMomentumX: FloatArray = FluidSim.f0

    @NotSerializedProperty
    private var tmpHuX = FluidSim.f0

    @NotSerializedProperty
    var fluidMomentumY: FloatArray = FluidSim.f0

    @NotSerializedProperty
    private var tmpHuY = FluidSim.f0

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float, minFluidHeight: Float) {
        val w = width
        val h = height
        val targetSize = (w + 2) * (h + 2)
        fluidHeight = FloatArray(targetSize)
        fluidMomentumX = FloatArray(targetSize)
        fluidMomentumY = FloatArray(targetSize)
        tmpH = FloatArray(targetSize)
        tmpHuX = FloatArray(targetSize)
        tmpHuY = FloatArray(targetSize)
        bathymetry = FloatArray(targetSize)
        setup.fillHeight(w, h, fluidHeight)
        setup.fillBathymetry(w, h, bathymetry)
        setup.fillMomentumX(w, h, fluidMomentumX)
        setup.fillMomentumY(w, h, fluidMomentumY)
        if (setup.hasBorder) {
            setup.applyBorder(w, h, fluidHeight, bathymetry, fluidMomentumX, fluidMomentumY)
        }
        setGhostOutflow(w, h, bathymetry)
    }

    private fun setGhostOutflow(width: Int, height: Int, v: FloatArray) {

        // set the ghost zone to be outflow conditions
        for (y in -1..height) {
            val outside = getIndex(-1, y)
            val inside = getIndex(0, y)
            v[outside] = v[inside]
        }
        for (y in -1..height) {
            val outside = getIndex(width, y)
            val inside = getIndex(width - 1, y)
            v[outside] = v[inside]
        }
        for (x in -1..width) {
            val outside = getIndex(x, -1)
            val inside = getIndex(x, 0)
            v[outside] = v[inside]
        }
        for (x in -1..width) {
            val outside = getIndex(x, height)
            val inside = getIndex(x, height - 1)
            v[outside] = v[inside]
        }
    }

    override fun setFromTextureRGBA32F(texture: Texture2D) {

        val (floats, data) = getPixels(texture)

        // extract data from floats
        val h = fluidHeight
        val hu = fluidMomentumX
        val hv = fluidMomentumY
        val b = bathymetry

        val width = texture.width
        val height = texture.height

        val stride = width + 2
        val offset = stride + 1 // (1,1)

        threadPool.processBalanced(0, height, false) { y0, y1 ->
            for (y in y0 until y1) {
                val iStart = (y * stride) + offset
                var j = (y * width) * 4
                val iEnd = iStart + width
                for (i in iStart until iEnd) {
                    h[i] = floats[j]
                    hu[i] = floats[j + 1]
                    hv[i] = floats[j + 2]
                    b[i] = floats[j + 3]
                    j += 4
                }
            }
        }

        Texture2D.bufferPool.returnBuffer(data)

        setGhostOutflow(width, height, bathymetry)

    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {

        val width = width
        val height = height

        if (x) {

            val h0 = fluidHeight
            val hu0 = fluidMomentumX

            setGhostOutflow(width, height, h0)
            setGhostOutflow(width, height, hu0)

            val h1 = copy(h0, tmpH)
            val hu1 = copy(hu0, tmpHuX)

            val b = bathymetry

            // step on x axis
            threadPool.processBalanced(-1, height + 1, 4) { y0, y1 ->
                val tmp4f = FluidSim.tmpV4ByThread.get()
                for (y in y0 until y1) {
                    var index0 = getIndex(-1, y)
                    for (xi in -1 until width) {
                        FWaveSolver.solve(
                            index0, index0 + 1, h0, hu0, b, h1, hu1,
                            gravity, scaling, tmp4f, minFluidHeight
                        )
                        index0++
                    }
                }
            }

        } else {

            val h1 = tmpH
            val hv1 = fluidMomentumY

            setGhostOutflow(width, height, h1)
            setGhostOutflow(width, height, hv1)

            // copy data, only height has changed, so only height needs to be switched
            val h2 = copy(h1, fluidHeight)
            val hv2 = copy(hv1, tmpHuY)

            // step on y axis
            val b = bathymetry
            val stride = width + 2
            threadPool.processBalanced(-1, height, 4) { y0, y1 ->
                val tmp4f = FluidSim.tmpV4ByThread.get()
                for (y in y0 until y1) {
                    var index0 = getIndex(0, y)
                    for (xi in 0 until width) {
                        FWaveSolver.solve(
                            index0, index0 + stride, h1, hv1, b, h2, hv2,
                            gravity, scaling, tmp4f, minFluidHeight
                        )
                        index0++
                    }
                }
            }

            // switch the buffers for the momentum
            var tmp = fluidMomentumX
            fluidMomentumX = tmpHuX
            tmpHuX = tmp

            tmp = fluidMomentumY
            fluidMomentumY = tmpHuY
            tmpHuY = tmp

        }
    }

    override fun updateStatistics(sim: FluidSim) {
        sim.maxMomentumX = getMaxValue(width, height, sim.coarsening, fluidMomentumX)
        sim.maxMomentumY = getMaxValue(width, height, sim.coarsening, fluidMomentumY)
        sim.maxSurfaceHeight = getMaxValue(width, height, sim.coarsening, fluidHeight, bathymetry)
    }

    override fun setZero() {
        val fluid = fluidHeight
        val bath = bathymetry
        for (i in 0 until min(fluid.size, bath.size)) {
            fluid[i] = max(0f, -bath[i])
        }
        fluidMomentumX.fill(0f)
        fluidMomentumY.fill(0f)
    }

    @Suppress("unused")
    override fun getSurfaceHeightAt(x: Int, y: Int): Float {
        val index = getIndex(x, y)
        return fluidHeight[index] + bathymetry[index]
    }

    @Suppress("unused")
    override fun getFluidHeightAt(x: Int, y: Int): Float {
        return fluidHeight[getIndex(x, y)]
    }

    @Suppress("unused")
    override fun getMomentumXAt(x: Int, y: Int): Float {
        return fluidMomentumX[getIndex(x, y)]
    }

    @Suppress("unused")
    override fun getMomentumYAt(x: Int, y: Int): Float {
        return fluidMomentumY[getIndex(x, y)]
    }

    override fun computeMaxVelocity(gravity: Float, minFluidHeight: Float): Float {
        val h = fluidHeight
        val hu = fluidMomentumX
        val hv = fluidMomentumY
        val width = width
        val height = height
        var maxVelocity = 0f
        // check for out of bounds conditions
        h[getIndex(width, height, width, height)]
        threadPool.processBalanced(0, height, true) { y0, y1 ->
            var maxVelocityI = 0f
            for (y in y0 until y1) {
                var index = getIndex(0, y, width, height)
                @Suppress("unused")
                for (x in 0 until width) {
                    val fluidHeight = h[index]
                    if (fluidHeight > minFluidHeight) {
                        val impulse = max(abs(hu[index]), abs(hv[index]))
                        val velocity = impulse / fluidHeight
                        val expectedVelocity = velocity + sqrt(gravity * fluidHeight)
                        if (expectedVelocity > maxVelocityI) {
                            /*if (expectedVelocity > 1e6) {
                                LOGGER.warn("Huge velocity at $x,$y from h: $fluidHeight, i: $impulse -> v: $velocity, exp-v: $expectedVelocity")
                            }*/
                            maxVelocityI = expectedVelocity
                        }
                    }
                    index++
                }
            }
            synchronized(threadPool) {
                maxVelocity = max(maxVelocity, maxVelocityI)
            }
        }
        return maxVelocity
    }

    override fun supportsMesh(): Boolean = true

    /**
     * @param w width with ghost cells
     * @param h height with ghost cells
     * @param cw coarse width with ghost cells
     * @param ch like cw
     * */
    override fun createFluidMesh(
        w: Int, h: Int,
        cw: Int, ch: Int,
        cellSize: Float, scale: Int,
        fluidHeightScale: Float,
        visualisation: Visualisation,
        colorMap: ColorMap?, colorMapScale: Float,
        maxVisualizedValueInternally: Float,
        mesh: ProceduralMesh
    ) {
        createFluidMesh(
            w, h, cw, ch, cellSize, scale, fluidHeightScale,
            fluidHeight, fluidMomentumX, fluidMomentumY, bathymetry,
            visualisation, colorMap, colorMapScale, maxVisualizedValueInternally, mesh
        )
    }

    override fun supportsTexture(): Boolean = true

    override fun requestFluidTexture(cw: Int, ch: Int): Texture2D {
        fluidTexture.setSize(cw, ch)
        val buffer = fbPool[fluidTexture.width * fluidTexture.height * 4, false, false]
        val data = createTextureData(width + 2, height + 2, cw + 2, ch + 2, this, buffer)
        fluidTexture.createRGBA(data, false)
        fbPool.returnBuffer(buffer)
        return fluidTexture
    }

    override fun supportsAsyncCompute(): Boolean = true

    override fun destroy() {
        fluidTexture.destroy()
    }

    companion object {

        @Suppress("unused")
        private val LOGGER = LogManager.getLogger(CPUEngine::class)

        val fbPool = FloatArrayPool(8)

        fun getPixels(texture: Texture2D): Pair<FloatBuffer, ByteBuffer> {

            val width = texture.width
            val height = texture.height

            val data = Texture2D.bufferPool[width * height * 4 * 4, false, false]
                .order(ByteOrder.nativeOrder())
            val floats = data.asFloatBuffer()
            floats.position(0)
            floats.limit(width * height * 4)

            GFX.check()

            Texture2D.bindTexture(texture.target, texture.pointer)

            setReadAlignment(width * height * 4)
            glGetTexImage(texture.target, 0, GL_RGBA, GL_FLOAT, floats)

            return Pair(floats, data)

        }

        fun copy(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
            if (src.size * 4 >= 1_000_000) {
                threadPool.processBalanced(0, min(src.size, dst.size), false) { i0, i1 ->
                    System.arraycopy(src, i0, dst, i0, i1 - i0)
                }
            } else {
                System.arraycopy(src, 0, dst, 0, min(src.size, dst.size))
            }
            return dst
        }

    }

}