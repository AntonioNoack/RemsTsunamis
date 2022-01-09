package me.anno.tsunamis.io

import me.anno.image.Image
import me.anno.utils.Clock
import me.anno.utils.maths.Maths.clamp
import me.anno.utils.maths.Maths.mix
import ucar.ma2.DataType
import ucar.nc2.Variable
import java.awt.image.BufferedImage
import kotlin.math.*

class VariableImage(variable: Variable) : Image(
    getWidth(variable),
    getHeight(variable),
    1, false
) {

    val data: FloatArray

    private val min: Float
    private val max: Float

    init {

        val c = Clock()
        val shape = variable.shape
        val dataArray = if (shape.size <= 2) variable.read()
        else variable.read(
            IntArray(shape.size), // offset
            IntArray(shape.size) { // slice size
                if (it < shape.size - 2) 1 else shape[it]
            })
        c.stop("Variable.read()")
        val floats = dataArray.get1DJavaArray(DataType.FLOAT) as FloatArray
        c.stop("get1DJavaArray")

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        clearNaNs(width, height, floats)
        c.stop("Clear NaNs")

        for (f in floats) {
            if (f.isFinite()) {
                if (f < min) min = f
                if (f > max) max = f
            }
        }
        c.stop("Min/Max")

        this.min = min
        this.max = max
        this.data = floats
    }

    private val wm2 = width - 2f
    private val hm2 = height - 2f

    private val invDelta255x = 255f / max(1e-38f, max(max, -min))

    override fun getRGB(p0: Int): Int {
        // if there is a special name like height or bathymetry, we could apply different color maps
        val v = data[p0]
        val f0255 = abs(v) * invDelta255x
        return if (f0255.isFinite()) {
            val baseColor = if (v < 0f) 0x10000 else 0x10101
            f0255.roundToInt() * baseColor or 0xff.shl(24)
        } else 0xffff00ff.toInt()
    }

    fun getValue(x: Int, y: Int): Float {
        return data[x + y * width]
    }

    fun getValue(x: Float, y: Float): Float {
        val x0 = clamp(floor(x), 0f, wm2)
        val y0 = clamp(floor(y), 0f, hm2)
        val stride = width
        val i = x0.toInt() + y0.toInt() * stride
        val fx = x - x0
        val fy = y - y0
        return mix(
            mix(data[i], data[i + 1], fx),
            mix(data[i + stride], data[i + stride + 1], fx),
            fy
        )
    }

    override fun createBufferedImage(dstWidth: Int, dstHeight: Int): BufferedImage {
        val image = BufferedImage(dstWidth, dstHeight, 1)
        val raster = image.raster.dataBuffer
        val width = width
        val height = height
        // quick & dirty without averaging
        // for faster previews
        for (y in 0 until dstHeight) {
            var i = y * dstWidth
            for (x in 0 until dstWidth) {
                raster.setElem(i++, getRGB(x * width / dstWidth, y * height / dstHeight))
            }
        }
        return image
    }

    companion object {

        fun getWidth(variable: Variable): Int {
            val shape = variable.shape
            return if (shape.isEmpty()) 1 else shape.last()
        }

        fun getHeight(variable: Variable): Int {
            val shape = variable.shape
            return if (shape.size < 2) 1 else shape[shape.size - 2]
        }

        fun clearNaNs(w: Int, h: Int, floats: FloatArray) {
            val nanIndices = HashSet<Int>()
            for (i in floats.indices) {
                if (!floats[i].isFinite()) {
                    nanIndices.add(i)
                }
            }
            if (nanIndices.isNotEmpty()) {
                // shuffle them, such that no value is propagated along the border for too long
                for (i in nanIndices.shuffled()) {
                    val x0 = i % w
                    val y0 = i / w
                    var sum = 0f
                    var div = 0
                    for (y in max(y0 - 2, 0) until min(y0 + 3, h)) {
                        for (x in max(x0 - 2, 0) until min(x0 + 3, w)) {
                            val vi = floats[i + (x - x0) + (y - y0) * w]
                            if (vi.isFinite()) {
                                sum += vi
                                div++
                            }
                        }
                    }
                    if (div > 0) {
                        sum /= div
                    }
                    floats[i] = sum
                }
            }
        }
    }

}