package me.anno.tsunamis.engine

import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.ecs.components.mesh.terrain.TerrainUtils
import me.anno.gpu.pipeline.CullMode
import me.anno.gpu.texture.Texture2D
import me.anno.maths.Maths
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.FluidSim.Companion.coarseIndexToFine
import me.anno.tsunamis.FluidSim.Companion.f0
import me.anno.tsunamis.FluidSimMod.Companion.linColorMap
import me.anno.tsunamis.Visualisation
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.setups.FluidSimSetup
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

@Suppress("unused")
abstract class TsunamiEngine(val width: Int, val height: Int) {

    var bathymetry: FloatArray = f0

    abstract fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float, minFluidHeight: Float)

    open fun step(gravity: Float, scaling: Float, minFluidHeight: Float) {
        halfStep(gravity, scaling, minFluidHeight, true)
        halfStep(gravity, scaling, minFluidHeight, false)
    }

    open fun isCompatible(engine: TsunamiEngine): Boolean {
        return engine.width == width && engine.height == height &&
                engine::class == this::class
    }

    abstract fun setFromTextureRGBA32F(texture: Texture2D)

    abstract fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean)

    abstract fun supportsAsyncCompute(): Boolean

    open fun synchronize() {}

    abstract fun setZero()

    abstract fun supportsMesh(): Boolean

    abstract fun supportsTexture(): Boolean

    abstract fun destroy()

    open fun getSurfaceHeightAt(x: Int, y: Int): Float {
        return getFluidHeightAt(x, y) + getBathymetryAt(x, y)
    }

    abstract fun getFluidHeightAt(x: Int, y: Int): Float

    abstract fun getMomentumXAt(x: Int, y: Int): Float

    abstract fun getMomentumYAt(x: Int, y: Int): Float

    abstract fun computeMaxVelocity(gravity: Float, minFluidHeight: Float): Float

    open fun updateStatistics(sim: FluidSim) {}

    fun getBathymetryAt(x: Int, y: Int): Float {
        return bathymetry[getIndex(x, y)]
    }

    fun getIndex(x: Int, y: Int): Int {
        val width = width
        val height = height
        val lx = Maths.clamp(x + 1, 0, width + 1)
        val ly = Maths.clamp(y + 1, 0, height + 1)
        return lx + ly * (width + 2)
    }

    open fun createFluidMesh(
        w: Int, h: Int, cw: Int, ch: Int, cellSize: Float, scale: Int,
        fluidHeightScale: Float,
        visualisation: Visualisation,
        colorMap: ColorMap?, colorMapScale: Float,
        maxVisualizedValueInternally: Float,
        mesh: ProceduralMesh
    ) {
        throw RuntimeException("Operation not supported")
    }

    /**
     * returns the fluid data,
     * typically without ghost cells
     * @param cw if coarsening is to be applied (width != cw), this function can do it as well; without ghost cells
     * @param ch see cw
     * */
    open fun requestFluidTexture(cw: Int, ch: Int): Texture2D {
        throw RuntimeException("Operation not supported")
    }

    fun createBathymetryMesh(
        w: Int, h: Int, cw: Int, ch: Int, scale: Int, cellSize: Float,
        colorMap: ColorMap?, colorMapScale: Float,
        culling: CullMode, mesh: ProceduralMesh
    ) {
        val bathymetry = bathymetry
        val normalY = cellSize * 2f * (if (culling == CullMode.BACK) -1f else +1f)
        val invCMScale = 1f / colorMapScale
        val mesh2 = mesh.getMesh()
        TerrainUtils.generateRegularQuadHeightMesh(
            cw - 2, ch - 2, 1 + cw, cw,
            culling == CullMode.BACK, // todo why is this not flipping the order?
            cellSize, mesh2,
            object : TerrainUtils.HeightMap {
                override fun get(it: Int): Float {
                    val i = coarseIndexToFine(w, h, cw, ch, it)
                    return bathymetry[i]
                }
            },
            object : TerrainUtils.NormalMap {
                override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                    val cx = min(x * w / cw, w - 1)
                    val cy = min(y * h / ch, h - 1)
                    dst.x = getBathymetryAt(cx + scale, cy) - getBathymetryAt(cx - scale, cy)
                    dst.y = normalY
                    dst.z = getBathymetryAt(cx, cy + scale) - getBathymetryAt(cx, cy - scale)
                    dst.normalize()
                }
            },
            if (colorMap == null) object : TerrainUtils.ColorMap {
                override fun get(it: Int): Int = -1
            } else object : TerrainUtils.ColorMap {
                override fun get(it: Int): Int {
                    val i = coarseIndexToFine(w, h, cw, ch, it)
                    return colorMap.getColor(bathymetry[i] * invCMScale)
                }
            }
        )
        if (culling == CullMode.BACK) {
            // todo neither disabling nor enabling it works... this is cursed...
            // todo is something changing the order???
            mesh2.indices!!.reverse()
            mesh2.invalidateGeometry()
        }
        if (culling == CullMode.BOTH) {
            // add inverse positions, normals and indices
            val oldPos = mesh2.positions!!
            val oldNor = mesh2.normals!!
            val oldIdx = mesh2.indices!!
            val newPos = FloatArray(oldPos.size * 2)
            val newNor = FloatArray(oldNor.size * 2)
            val newIdx = IntArray(oldIdx.size * 2)
            System.arraycopy(oldPos, 0, newPos, 0, oldPos.size)
            System.arraycopy(oldPos, 0, newPos, oldPos.size, oldPos.size)
            System.arraycopy(oldNor, 0, newNor, 0, oldNor.size)
            System.arraycopy(oldNor, 0, newNor, oldNor.size, oldNor.size)
            // adjust y of normals
            for (i in 1 until newNor.size step 3) {
                newNor[i] = -newNor[i]
            }
            System.arraycopy(oldIdx, 0, newIdx, 0, oldIdx.size)
            newIdx.reverse()
            System.arraycopy(oldIdx, 0, newIdx, 0, oldIdx.size)
            mesh2.positions = newPos
            mesh2.normals = newNor
            mesh2.indices = newIdx
            mesh2.invalidateGeometry()
        }
    }

    companion object {

        fun setGhostOutflow(width: Int, height: Int, v: FloatArray) {

            // set the ghost zone to be outflow conditions
            for (y in -1..height) {
                val outside = getIndex(-1, y, width, height)
                val inside = getIndex(0, y, width, height)
                v[outside] = v[inside]
            }
            for (y in -1..height) {
                val outside = getIndex(width, y, width, height)
                val inside = getIndex(width - 1, y, width, height)
                v[outside] = v[inside]
            }
            for (x in -1..width) {
                val outside = getIndex(x, -1, width, height)
                val inside = getIndex(x, 0, width, height)
                v[outside] = v[inside]
            }
            for (x in -1..width) {
                val outside = getIndex(x, height, width, height)
                val inside = getIndex(x, height - 1, width, height)
                v[outside] = v[inside]
            }
        }

        fun getIndex(x: Int, y: Int, width: Int, height: Int): Int {
            val lx = Maths.clamp(x + 1, 0, width + 1)
            val ly = Maths.clamp(y + 1, 0, height + 1)
            return lx + ly * (width + 2)
        }

        private fun getWaterColor(height: Float): Int {
            return Maths.mixARGB(0xabbee3, 0x103273, Maths.clamp(height * 0.1f, 0f, 1f))
        }

        private fun getColor11(f: Float): Int {
            return linColorMap.getColor(f)
        }

        /**
         * @param w width with ghost cells
         * @param h like w
         * @param cw coarse width with ghost cells
         * @param ch like cw
         * */
        fun createFluidMesh(
            w: Int,
            h: Int,
            cw: Int,
            ch: Int,
            cellSize: Float,
            scale: Int,
            fluidHeightScale: Float,
            fluidHeight: FloatArray,
            fluidMomentumX: FloatArray,
            fluidMomentumY: FloatArray,
            bathymetry: FloatArray,
            visualisation: Visualisation,
            colorMap: ColorMap?,
            colorMapScale: Float,
            maxVisualizedValueInternally: Float,
            mesh: ProceduralMesh,
        ) {
            val size = w * h
            fluidHeight[size - 1]
            fluidMomentumX[size - 1]
            fluidMomentumY[size - 1]
            bathymetry[size - 1]
            val invCMScale = 1f / colorMapScale
            val getColor: TerrainUtils.ColorMap = when (visualisation) {
                Visualisation.HEIGHT_MAP -> {
                    if (colorMap == null) object : TerrainUtils.ColorMap {
                        override fun get(it: Int) = getWaterColor(fluidHeight[it])
                    } else object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            val fh = fluidHeight[i]
                            return if (fh > 0f) {// under water, surface color
                                colorMap.getColor(-fh * invCMScale)
                            } else colorMap.getColor(bathymetry[i] * invCMScale) // land color
                        }
                    }
                }
                Visualisation.MOMENTUM_X -> {
                    val momentumScale = 1f / max(maxVisualizedValueInternally, 1e-38f)
                    if (colorMap == null) object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            return getColor11(fluidMomentumX[i] * momentumScale)
                        }
                    } else object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            val fh = fluidHeight[i]
                            return if (fh > 0f) {// under water, surface color
                                getColor11(fluidMomentumX[i] * momentumScale)
                            } else colorMap.getColor(bathymetry[i] * invCMScale) // land color
                        }
                    }
                }
                Visualisation.MOMENTUM_Y -> {
                    val momentumScale = 1f / max(maxVisualizedValueInternally, 1e-38f)
                    if (colorMap == null) object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            return getColor11(fluidMomentumY[i] * momentumScale)
                        }
                    } else object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            val fh = fluidHeight[i]
                            return if (fh > 0f) {// under water, surface color
                                getColor11(fluidMomentumY[i] * momentumScale)
                            } else colorMap.getColor(bathymetry[i] * invCMScale) // land color
                        }
                    }
                }
                Visualisation.MOMENTUM -> {
                    val momentumScale = 1f / max(maxVisualizedValueInternally, 1e-38f)
                    if (colorMap == null) object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            return getColor11(Maths.length(fluidMomentumX[i], fluidMomentumY[i]) * momentumScale)
                        }
                    } else object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            val fh = fluidHeight[i]
                            return if (fh > 0f) {// under water, surface color
                                getColor11(Maths.length(fluidMomentumX[i], fluidMomentumY[i]) * momentumScale)
                            } else colorMap.getColor(bathymetry[i] * invCMScale) // land color
                        }
                    }
                }
                Visualisation.WATER_SURFACE -> {
                    val heightScale = 1f / max(maxVisualizedValueInternally, 1e-38f)
                    if (colorMap == null) object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            return getColor11((fluidHeight[i] + bathymetry[i]) * heightScale)
                        }
                    } else object : TerrainUtils.ColorMap {
                        override fun get(it: Int): Int {
                            val i = coarseIndexToFine(w, h, cw, ch, it)
                            val fh = fluidHeight[i]
                            val ba = bathymetry[i]
                            return if (fh > 0f) {// under water, surface color
                                getColor11((fh + ba) * heightScale)
                            } else colorMap.getColor(ba * invCMScale) // land color
                        }
                    }
                }
            }
            mesh.materials = emptyList()
            fun getSurfaceHeightAt(x: Int, y: Int): Float {
                val index = getIndex(x, y, w - 2, h - 2)
                val fh = fluidHeight[index]
                val surface = fh + bathymetry[index]
                return if (fh > 0f) surface * fluidHeightScale else surface
            }
            TerrainUtils.generateRegularQuadHeightMesh(
                cw - 2, ch - 2, cw + 1, cw,
                false, cellSize, mesh.getMesh(),
                object : TerrainUtils.HeightMap {
                    override fun get(it: Int): Float {
                        val index = coarseIndexToFine(w, h, cw, ch, it)
                        val fh = fluidHeight[index]
                        val surface = fh + bathymetry[index]
                        return if (fh > 0f) surface * fluidHeightScale else surface
                    }
                },
                object : TerrainUtils.NormalMap {
                    override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                        val cx = min(x * w / cw, w - 1)
                        val cy = min(y * h / ch, h - 1)
                        dst.x = getSurfaceHeightAt(cx + scale, cy) - getSurfaceHeightAt(cx - scale, cy)
                        dst.y = cellSize * scale * 2f
                        dst.z = getSurfaceHeightAt(cx, cy + scale) - getSurfaceHeightAt(cx, cy - scale)
                        dst.normalize()
                    }
                },
                getColor
            )
        }


        fun getMaxValue(width: Int, height: Int, coarsening: Int, data: FloatArray): Float {
            var min = 0f
            var max = 0f
            // this is much too slow for large sizes,
            // so only iterate over the output image
            val cw = Maths.ceilDiv(width, coarsening)
            val ch = Maths.ceilDiv(height, coarsening)
            // we could parallelize this
            // but probably not needed,
            // because we have to stream this data currently anyways
            try {
                for (y in 0 until ch) {
                    var i = (y * height / ch) * width
                    for (x in 0 until cw) {
                        val f = data[i]
                        if (f.isFinite()) {
                            if (f < min) min = f
                            if (f > max) max = f
                        }
                        i += coarsening
                    }
                }
            } catch (e: ArrayIndexOutOfBoundsException) {
                // we don't care, as long as it's approx. correct
            }
            return max(-min, max)
        }

        fun getMaxValue(
            width: Int, height: Int, coarsening: Int,
            fluid: FloatArray, bathymetry: FloatArray
        ): Float {
            var min = 0f
            var max = 0f
            // this is much too slow for large sizes,
            // so only iterate over the output image
            // we could parallelize this,
            // but it wouldn't bring that much probably
            if (coarsening == 1) {
                for (i in fluid.indices) {
                    val f = fluid[i]
                    if (f > 0f) {
                        val s = f + bathymetry[i]
                        if (s.isFinite()) {
                            if (s < min) min = s
                            if (s > max) max = s
                        }
                    }
                }
            } else {
                val cw = Maths.ceilDiv(width, coarsening)
                val ch = Maths.ceilDiv(height, coarsening)
                for (y in 0 until ch) {
                    for (x in 0 until cw) {
                        val i = coarseIndexToFine(width, height, cw, ch, x + y * cw)
                        val f = fluid[i]
                        if (f > 0f) {
                            val s = f + bathymetry[i]
                            if (s.isFinite()) {
                                if (s < min) min = s
                                if (s > max) max = s
                            }
                        }
                    }
                }
            }
            return max(-min, max)
        }

    }

}