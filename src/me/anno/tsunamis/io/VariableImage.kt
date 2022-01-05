package me.anno.tsunamis.io

import me.anno.image.Image
import me.anno.utils.maths.Maths.clamp
import me.anno.utils.maths.Maths.mix
import ucar.ma2.DataType
import ucar.nc2.Variable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VariableImage(variable: Variable) : Image(
    getWidth(variable),
    getHeight(variable),
    1, false
) {

    val data: FloatArray

    private val min: Float
    private val max: Float

    init {
        val floats = synchronized(NetCDFMutex) {
            val shape = variable.shape
            val dataArray = if (shape.size <= 2) variable.read()
            else variable.read(
                IntArray(shape.size), // offset
                IntArray(shape.size) { // slice size
                    if (it < shape.size - 2) 1 else shape[it]
                })
            dataArray.get1DJavaArray(DataType.FLOAT) as FloatArray
        }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        clearNaNs(width, height, floats)
        for (f in floats) {
            if (f.isFinite()) {
                if (f < min) min = f
                if (f > max) max = f
            }
        }
        this.min = min
        this.max = max
        this.data = floats
    }

    private val wm2 = width - 2f
    private val hm2 = height - 2f

    private val invDelta255 = 255f / (max - min)

    override fun getRGB(p0: Int): Int {
        // if there is a special name like height or bathymetry, we could apply different color maps
        val v = data[p0]
        val f01 = (v - min) * invDelta255
        return if (f01.isFinite()) {
            0xff.shl(24) or f01.roundToInt() * 0x10101 // gray scale
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