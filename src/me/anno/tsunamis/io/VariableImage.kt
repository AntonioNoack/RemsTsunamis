package me.anno.tsunamis.io

import me.anno.image.Image
import me.anno.maths.Maths.clamp
import me.anno.maths.Maths.mix
import me.anno.tsunamis.FluidSimMod.Companion.linColorMap
import me.anno.utils.Clock
import org.apache.logging.log4j.LogManager
import ucar.ma2.DataType
import ucar.nc2.Variable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class VariableImage(variable: Variable) : Image(
    getWidth(variable), getHeight(variable),
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

        flipTextureY(width, height, floats)
        c.stop("Flip Texture")

        this.min = min
        this.max = max
        this.data = floats
    }

    private val wm2 = width - 2f
    private val hm2 = height - 2f

    private val colorMap = linColorMap.clone(min, max)

    override fun getRGB(index: Int): Int {
        // if there is a special name like height or bathymetry, we could apply different color maps
        return colorMap.getColor(data[index])
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

        private val LOGGER = LogManager.getLogger(VariableImage::class)

        private fun getWidth(variable: Variable): Int {
            val shape = variable.shape
            return if (shape.isEmpty()) 1 else shape.last()
        }

        private fun getHeight(variable: Variable): Int {
            val shape = variable.shape
            return if (shape.size < 2) 1 else shape[shape.size - 2]
        }

        private fun flipTextureY(width: Int, height: Int, values: FloatArray) {
            val hm1 = height - 1
            val tmp = FloatArray(width)
            for (y in 0 until height / 2) {
                val srcIndex = y * width
                val dstIndex = (hm1 - y) * width
                values.copyInto(tmp, 0, srcIndex, srcIndex + width) // src -> tmp
                values.copyInto(values, srcIndex, dstIndex, dstIndex + width) // dst -> src
                tmp.copyInto(values, dstIndex) // tmp -> dst
            }
        }

        private fun findFiniteAverage(w: Int, h: Int, values: FloatArray, i: Int): Float {
            val xc = i % w
            val yc = i / w
            var sum = 0f
            var weight = 0
            val x0 = max(xc - 2, 0)
            val x1 = min(xc + 2, w - 1)
            val y0 = max(yc - 2, 0)
            val y1 = min(yc + 2, h - 1)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val vi = values[i + (x - xc) + (y - yc) * w]
                    if (vi.isFinite()) {
                        sum += vi
                        weight++
                    }
                }
            }
            if (weight > 0) {
                sum /= weight
            }
            return sum
        }

        private fun clearNaNs(w: Int, h: Int, values: FloatArray) {
            val invalidIndices = HashSet<Int>()
            for (i in values.indices) {
                if (!values[i].isFinite()) {
                    invalidIndices.add(i)
                }
            }
            LOGGER.info("Found ${invalidIndices.size} invalid values")
            // shuffle them, such that no value is propagated along the border for too long
            for (i in invalidIndices.shuffled()) {
                values[i] = findFiniteAverage(w, h, values, i)
            }
        }
    }

}