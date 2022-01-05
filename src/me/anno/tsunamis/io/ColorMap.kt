package me.anno.tsunamis.io

import me.anno.cache.CacheSection
import me.anno.image.Image
import me.anno.image.ImageWriter
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.xml.XMLElement
import me.anno.io.xml.XMLReader
import me.anno.ui.editor.color.ColorSpace
import me.anno.ui.editor.color.spaces.HSV
import me.anno.utils.Color.a
import me.anno.utils.Color.b
import me.anno.utils.Color.g
import me.anno.utils.Color.r
import me.anno.utils.Color.rgba
import me.anno.utils.files.Files.use
import me.anno.utils.maths.Maths.max
import me.anno.utils.maths.Maths.min
import me.anno.utils.maths.Maths.mix
import me.anno.utils.maths.Maths.mixARGB
import me.anno.utils.structures.arrays.FloatArrayList
import me.anno.utils.structures.arrays.IntArrayList
import org.joml.Vector3f
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_ARGB
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.InputStream

/**
 * a customizable color map like, like for ParaView
 * usually specified with an XML file, where entries are Points with the properties x (height in meters), r (red, 0-1), g (green, 0-1), b (blue, 0-1)
 * */
class ColorMap(
    val colors: IntArray,
    val heights: FloatArray
) : Image(colors.size * previewScale, 1, 3,
        !colors.all { it.a() == 0 } || !colors.all { it.a() == 255 }) {

    val min = heights.first()
    val max = heights.last()

    /**
     * linear interpolation between colors
     * */
    fun getColor(height: Float): Int {
        if (colors.size == 1) return colors[0]
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

    override fun getRGB(index: Int): Int {
        if (colors.size == 1) return colors[0]
        val relativeIndex = index.toFloat() / (width - 1f)
        return getColor(mix(min, max, relativeIndex))
    }

    override fun createBufferedImage(dstWidth: Int, dstHeight: Int): BufferedImage {
        val image = BufferedImage(dstWidth, 1, if (hasAlphaChannel) TYPE_INT_ARGB else TYPE_INT_RGB)
        val raster = image.raster
        val data = raster.dataBuffer
        for (index in 0 until dstWidth) {
            val relativeIndex = index.toFloat() / (dstWidth - 1f)
            data.setElem(index, getColor(mix(min, max, relativeIndex)))
        }
        return image
    }

    override fun destroy() {

    }

    companion object {

        private const val previewScale = 5

        private val cache = CacheSection("ColorMaps")

        private fun convertToRGB(
            colors: IntArrayList,
            heights: FloatArrayList,
            space: ColorSpace,
            scale: Int
        ): ColorMap {
            val oldSize = heights.size
            val newSize = min(max(oldSize, oldSize * scale - 1), 1024)
            val dstColors = IntArray(newSize)
            val dstHeights = FloatArray(newSize)
            val rgb0 = Vector3f()
            val hsv0 = Vector3f()
            val rgb1 = Vector3f()
            val hsv1 = Vector3f()
            val rgbResult = Vector3f()
            val scale2 = (oldSize - 1f) / (newSize - 1f)
            for (i in 0 until newSize) {
                val indexF = i * scale2
                val index = min(indexF.toInt(), oldSize - 1)
                val factor = indexF - index
                val height = mix(heights[index], heights[index + 1], factor)
                val c0 = colors[index]
                val c1 = colors[index + 1]
                rgb0.set(c0.r() / 255f, c0.g() / 255f, c0.b() / 255f)
                space.fromRGB(rgb0, hsv0)
                rgb1.set(c1.r() / 255f, c1.g() / 255f, c1.b() / 255f)
                space.fromRGB(rgb1, hsv1)
                space.toRGB(hsv0.lerp(hsv1, factor), rgbResult)
                val a = mix(c0.a() / 255f, c1.a() / 255f, factor)
                dstHeights[i] = height
                dstColors[i] = rgba(rgbResult.x, rgbResult.y, rgbResult.z, a)
            }
            return ColorMap(dstColors, dstHeights)
        }

        private fun process(
            node: XMLElement,
            colors: IntArrayList,
            heights: FloatArrayList,
            space: ColorSpace
        ): ColorSpace {
            // x="-6500" o="1" r="0.266666666666667" g="0.333333333333333" b="1"
            var space0 = space
            try {
                val space1 = node["space"]
                if (space1 != null) space0 = when (space1.lowercase()) {
                    "rgb" -> RGBColorSpace
                    "hsl", "hsv" -> HSV
                    else -> space0
                }
                if (node.type.equals("point", true)) {
                    val height = node["x"]!!.toFloat()
                    // values between 0 and 1
                    val r = node["r"]!!.toFloat()
                    val g = node["g"]!!.toFloat()
                    val b = node["b"]!!.toFloat()
                    val a = node["o"]?.toFloat() ?: 1f
                    val color = rgba(r, g, b, a)
                    heights += height
                    colors += color
                } else {
                    // might be a wrapper node
                    for (child in node.children) {
                        if (child is XMLElement) {
                            space0 = process(child, colors, heights, space0)
                        }
                    }
                }
            } catch (e: NullPointerException) {
                // !!
            } catch (e: NumberFormatException) {
                // toFloat()
            }
            return space0
        }

        fun read(input: InputStream): ColorMap? {
            val xmlData = XMLReader.parse(input)
            return if (xmlData is XMLElement) {
                val colors = IntArrayList(16)
                val heights = FloatArrayList(16)
                val space = process(xmlData, colors, heights, RGBColorSpace)
                if (colors.size > 0) {
                    if (space === RGBColorSpace) ColorMap(colors.toIntArray(), heights.toFloatArray())
                    else convertToRGB(colors, heights, space, 5)
                } else null
            } else null
        }

        fun read(file: FileReference, async: Boolean): ColorMap? {
            return cache.getFileEntry(file, false, 10_000, async) { file1, _ ->
                use(file1.inputStream()) { read(it) }
            } as? ColorMap
        }

        @JvmStatic
        fun main(args: Array<String>) {
            // a test whether it works
            val map = read(
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