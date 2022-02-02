package me.anno.tsunamis.setups

import me.anno.ecs.Component
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.tsunamis.FluidSim.Companion.threadPool
import kotlin.math.max

open class FluidSimSetup : Component() {

    var hasBorder = true
    var borderHeight = 10f

    open fun isReady(): Boolean = true

    open fun getPreferredCellSizeMeters(): Float = 1f

    open fun getPreferredNumCellsX(): Int = 10

    open fun getPreferredNumCellsY(): Int = 10

    open fun getHeight(x: Int, y: Int, w: Int, h: Int) = 1f

    open fun getBathymetry(x: Int, y: Int, w: Int, h: Int) = 0f

    open fun getDisplacement(x: Int, y: Int, w: Int, h: Int) = 0f

    open fun getMomentumX(x: Int, y: Int, w: Int, h: Int) = 0f

    open fun getMomentumY(x: Int, y: Int, w: Int, h: Int) = 0f

    fun applyBorder(
        w: Int,
        h: Int,
        height: FloatArray,
        bathymetry: FloatArray,
        momentumX: FloatArray,
        momentumY: FloatArray
    ) {
        val stride = w + 2
        val bh = borderHeight
        val fh = max(0f, -bh)
        val yStride = h * stride
        for (x in 1 until w) {
            val i = x + stride
            val j = x + yStride
            height[i] = fh
            bathymetry[i] = bh
            momentumX[i] = 0f
            momentumY[i] = 0f
            height[j] = fh
            bathymetry[j] = bh
            momentumX[j] = 0f
            momentumY[j] = 0f
        }
        for (y in 1..h) {
            val i = y * stride + 1
            val j = i + stride - 3
            height[i] = fh
            bathymetry[i] = bh
            momentumX[i] = 0f
            momentumY[i] = 0f
            height[j] = fh
            bathymetry[j] = bh
            momentumX[j] = 0f
            momentumY[j] = 0f
        }
    }

    fun applyBorder(
        w: Int,
        h: Int,
        height: FloatArray,
    ) {
        val stride = w + 2
        val bh = borderHeight
        val fh = max(0f, -bh)
        val yStride = h * stride
        for (x in 1 until w) {
            val i = x + stride
            val j = x + yStride
            height[i] = fh
            height[j] = fh
        }
        for (y in 1..h) {
            val i = y * stride + 1
            val j = i + stride - 3
            height[i] = fh
            height[j] = fh
        }
    }

    open fun fillHeight(w: Int, h: Int, dst: FloatArray) {
        threadPool.processBalanced(-1, h + 1, 65536 / w) { y0, y1 ->
            val stride = w + 2
            for (y in y0 until y1) {
                var i = (y + 1) * stride
                for (x in -1..w) {
                    dst[i] = getHeight(x, y, w, h)
                    i++
                }
            }
        }
    }

    open fun fillBathymetry(w: Int, h: Int, dst: FloatArray) {
        threadPool.processBalanced(-1, h + 1, 65536 / w) { y0, y1 ->
            val stride = w + 2
            for (y in y0 until y1) {
                var i = (y + 1) * stride
                for (x in -1..w) {
                    dst[i] = getBathymetry(x, y, w, h) + getDisplacement(x, y, w, h)
                    i++
                }
            }
        }
    }

    open fun fillMomentumX(w: Int, h: Int, dst: FloatArray) {
        threadPool.processBalanced(-1, h + 1, 65536 / w) { y0, y1 ->
            val stride = w + 2
            for (y in y0 until y1) {
                var i = (y + 1) * stride
                for (x in -1..w) {
                    dst[i] = getMomentumX(x, y, w, h)
                    i++
                }
            }
        }
    }

    open fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        threadPool.processBalanced(-1, h + 1, 65536 / w) { y0, y1 ->
            val stride = w + 2
            for (y in y0 until y1) {
                var i = (y + 1) * stride
                for (x in -1..w) {
                    dst[i] = getMomentumY(x, y, w, h)
                    i++
                }
            }
        }
    }

    override fun clone(): FluidSimSetup {
        val clone = FluidSimSetup()
        copy(clone)
        return clone
    }

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as FluidSimSetup
        clone.hasBorder = hasBorder
        clone.borderHeight = borderHeight
    }

}