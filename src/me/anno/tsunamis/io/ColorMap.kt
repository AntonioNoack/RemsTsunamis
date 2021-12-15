package me.anno.tsunamis.io

import me.anno.cache.CacheData
import me.anno.cache.CacheSection
import me.anno.cache.data.ICacheData
import me.anno.image.ImageWriter
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.xml.XMLElement
import me.anno.io.xml.XMLReader
import me.anno.utils.Color.rgba
import me.anno.utils.files.Files.use
import me.anno.utils.maths.Maths.mix
import me.anno.utils.maths.Maths.mixARGB
import me.anno.utils.structures.arrays.FloatArrayList
import me.anno.utils.structures.arrays.IntArrayList

/**
 * a customizable color map like, like for ParaView
 * usually specified with an XML file, where entries are Points with the properties x (height in meters), r (red, 0-1), g (green, 0-1), b (blue, 0-1)
 * */
class ColorMap(val colors: IntArray, val heights: FloatArray) : ICacheData {

    val min = heights.first()
    val max = heights.last()

    /**
     * linear interpolation between colors
     * */
    fun getColor(height: Float): Int {
        if (height.isNaN()) return 0xff00ff
        var index = heights.binarySearch(height)
        if (index >= 0) return colors[index]
        index = -1 - index // height was not found -> use interpolation
        if (index + 1 >= colors.size) return colors.last() // color at the end
        val min = heights[index]
        val max = heights[index + 1]
        if (height < min || min >= max) return colors[index]
        if (height > max) return colors[index + 1]
        val factor = (height - min) / (max - min)
        return mixARGB(colors[index], colors[index + 1], factor)
    }

    override fun destroy() {

    }

    companion object {

        private val nullData = CacheData(null)

        private val cache = CacheSection("ColorMaps")
        fun getMap(file: FileReference, async: Boolean): ColorMap? {
            return cache.getFileEntry(file, false, 10_000, async) { file1, _ ->
                val xmlData = use(file.inputStream()) { XMLReader.parse(it) }
                if (xmlData is XMLElement) {
                    val colors = IntArrayList(16)
                    val heights = FloatArrayList(16)
                    fun process(node: XMLElement) {
                        // x="-6500" o="1" r="0.266666666666667" g="0.333333333333333" b="1"
                        try {
                            if (node.type.equals("point", true)) {
                                val height = node["x"]!!.toFloat()
                                // values between 0 and 1
                                val r = node["r"]!!.toFloat()
                                val g = node["g"]!!.toFloat()
                                val b = node["b"]!!.toFloat()
                                // let's ignore opacity for now
                                val color = rgba(r, g, b, 1f)
                                heights += height
                                colors += color
                            } else {
                                // might be a wrapper node
                                for (child in node.children) {
                                    if (child is XMLElement) {
                                        process(child)
                                    }
                                }
                            }
                        } catch (e: NullPointerException) {
                            // !!
                        } catch (e: NumberFormatException) {
                            // toFloat()
                        }
                    }
                    process(xmlData)
                    if (colors.size > 0) {
                        ColorMap(
                            colors.toIntArray(),
                            heights.toFloatArray()
                        )
                    } else nullData
                } else nullData
            } as? ColorMap
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val map = getMap(
                getReference(
                    "E:/Documents/Uni/Master/WS2122/05_gmt_paraview/" +
                            "gmt_colortables_for_paraview/globe.xml"
                ), false
            )!!
            val w = 100
            ImageWriter.writeImageInt(w, 1, false, "colorMap", 512) { x, _, _ ->
                map.getColor(mix(x / w.toFloat() * 1.1f - 0.05f, map.min, map.max))
            }
        }

    }
}