package me.anno.tsunamis.setups

import me.anno.cache.CacheData
import me.anno.cache.CacheSection
import me.anno.ecs.annotations.DebugProperty
import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.image.ImageWriter
import me.anno.image.colormap.LinearColorMap
import me.anno.io.csv.CSVReader
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.files.InvalidRef
import me.anno.io.serialization.SerializedProperty
import me.anno.maths.Maths.mix
import me.anno.maths.Maths.unmix
import me.anno.tsunamis.engine.TsunamiEngine.Companion.setGhostOutflow
import me.anno.tsunamis.setups.ShoreLine.toBathymetry
import me.anno.tsunamis.setups.ShoreLine.toFluidHeight
import me.anno.ui.base.components.Padding
import me.anno.utils.Sleep.waitUntil
import java.io.IOException
import kotlin.math.floor
import kotlin.math.sin

class GMTTrackSetup : FluidSimSetup() {

    @SerializedProperty
    var sourceFile: FileReference = InvalidRef

    @Range(0.0, Double.POSITIVE_INFINITY)
    @SerializedProperty
    var shoreCliffHeight = 20f

    var applyInterpolation = true
    var applyAveraging = true

    /**
     * relative start of the displacement, [0,1]
     * */
    @SerializedProperty
    var displacementStart = 0.1f

    /**
     * relative end of the displacement, [0,1]
     * */
    @SerializedProperty
    var displacementEnd = 0.2f

    /**
     * amplitude of displacement
     * */
    @SerializedProperty
    var displacementHeight = 5f

    @DebugProperty
    @Suppress("unused")
    val dataWidth
        get() = getData(sourceFile, true)?.second?.size ?: -1

    @DebugProperty
    @Suppress("unused")
    val dataHeight
        get() = 1

    override fun getPreferredCellSizeMeters(): Float {
        return getData(sourceFile, false)!!.first
    }

    override fun getPreferredNumCellsX(): Int {
        return getData(sourceFile, false)!!.second.size
    }

    override fun getPreferredNumCellsY(): Int {
        return 1
    }

    override fun isReady(): Boolean {
        return getData(sourceFile, true) != null
    }

    override fun fillMomentumX(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        return -getBathymetry(x, y, w, h)
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        val data = getData(sourceFile, false)!!.second
        val fx = x.toFloat() * data.size.toFloat() / w.toFloat()
        return data[fx]
    }

    override fun getDisplacement(x: Int, y: Int, w: Int, h: Int): Float {
        val relativePosition = x / (w - 1f)
        val normalizedPosition = unmix(displacementStart, displacementEnd, relativePosition)
        return if (normalizedPosition in 0f..1f) displacementHeight * sin(normalizedPosition * 2f * Math.PI.toFloat()) else 0f
    }

    override fun fillHeight(w: Int, h: Int, dst: FloatArray) {
        val data = getData(sourceFile, false)!!.second
        fillData(w, h, dst, data)
        val stride = w + 2
        val shoreMax = shoreCliffHeight
        for (i in stride until stride * 2) {
            dst[i] = toFluidHeight(dst[i], shoreMax)
        }
        duplicateData(w, h, dst)
    }

    override fun fillBathymetry(w: Int, h: Int, dst: FloatArray) {
        val data = getData(sourceFile, false)!!.second
        fillData(w, h, dst, data)
        val shoreMax = shoreCliffHeight
        if (shoreMax > 0f) {
            for (i in dst.indices) {
                dst[i] = toBathymetry(dst[i], shoreMax)
            }
        }
        val stride = w + 2
        val offset = 1 + stride
        for (x in 0 until w) {
            dst[x + offset] += getDisplacement(x, 0, w, h)
        }
        duplicateData(w, h, dst)
    }

    private fun fillData(w: Int, h: Int, dst: FloatArray, data: FloatArray) {
        dst.fill(0f)
        addData(w, dst, data)
        // duplicate values to the side
        duplicateData(w, h, dst)
    }

    private fun duplicateData(w: Int, h: Int, dst: FloatArray) {
        val stride = w + 2
        for (y in 1 until h) {
            val offset = (y + 1) * stride
            // src, dst
            System.arraycopy(dst, stride, dst, offset, stride)
        }
    }

    private fun addData(w: Int, dst: FloatArray, data: FloatArray) {

        // add variable data
        val ow = data.size
        val stride = w + 2
        val dstOffset = 1 + stride

        // for even better results, we could use averaging + interpolation on the borders
        // however, this is not really required, because we have full control over the simulated area size
        val originalIsLarger = ow > w
        if (originalIsLarger && applyAveraging) {
            // averaging
            var x0In = 0
            var i = dstOffset
            for (x in 0 until w) {
                val x1In = (x + 1) * ow / w
                var sum = 0f
                var j = x0In
                for (xi in x0In until x1In) {
                    sum += data[j++]
                }
                dst[i++] += sum / (x1In - x0In)
                x0In = x1In
            }
        } else if (!originalIsLarger && applyInterpolation) {
            val sx = ow.toFloat() / w
            var i = dstOffset
            for (x in 0 until w) {
                dst[i++] += data[x * sx]
            }
        } else {
            // just read the values from the correct positions
            var i = dstOffset
            for (x in 0 until w) {
                dst[i++] += data[x * ow / w]
            }
        }
    }

    operator fun FloatArray.get(index: Float): Float {
        if (index < 0f) return first()
        if (index >= size - 1f) return last()
        val floorI = floor(index)
        val index0 = floorI.toInt()
        return mix(this[index0], this[index0 + 1], index - floorI)
    }

    override fun clone(): GMTTrackSetup {
        val clone = GMTTrackSetup()
        copy(clone)
        return clone
    }

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as GMTTrackSetup
        clone.sourceFile = sourceFile
        clone.shoreCliffHeight = shoreCliffHeight
        clone.applyAveraging = applyAveraging
        clone.applyInterpolation = applyInterpolation
    }

    override val className: String = "Tsunamis/GMTTrackSetup"

    companion object {

        private val trackCache = CacheSection("GMTTrackCache")

        fun getData(file: FileReference, async: Boolean): Pair<Float, FloatArray>? {
            val data = trackCache.getFileEntry(file, false, 1000, async) { file1, _ ->
                val data = CSVReader.readNumerical(file1.readText(), ',', '\n', 0.0)
                val positions = data["track_location"]
                val bathymetry = data["height"] ?: throw IOException("Didn't find column 'height'")
                val size = bathymetry.size
                val cellSize =
                    if (positions != null) (positions.last() - positions.first()) / (bathymetry.size - 1f) else 1f
                val bathymetry2 = FloatArray(size) { bathymetry[it].toFloat() }
                CacheData(Pair(cellSize, bathymetry2))
            } as? CacheData<*>
            @Suppress("unchecked_cast")
            return data?.value as? Pair<Float, FloatArray>
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val setup = GMTTrackSetup()
            setup.hasBorder = false
            waitUntil(true) { setup.isReady() }
            val w = setup.dataWidth
            val h = 1
            val bath = FloatArray((w + 2) * (h + 2))
            setup.fillBathymetry(w, h, bath)
            setGhostOutflow(w, h, bath)
            val offset = (w + 2) + 1
            println("avg: ${bath.average()}, max: ${bath.maxOrNull()}, min: ${bath.minOrNull()}")
            ImageWriter.writeImageProfile(
                FloatArray(w) { bath[it + offset] }, w / 2, "bath-2.png",
                true, LinearColorMap.default, 0xffffff, 0xaaaaaa, false, Padding(10)
            )
            // ImageWriter.writeImageFloat(w + 2, h + 2, "bath.png", true, bath)
        }

    }

}