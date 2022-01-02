package me.anno.tsunamis.io

import me.anno.gpu.texture.Texture2D
import me.anno.image.Image
import me.anno.image.raw.IntImage
import me.anno.io.files.FileReference
import java.awt.image.BufferedImage

class LateinitVariableImage(val variableName: String, val file: FileReference) :
    Image(1, 1, 1, false) {

    private val image by lazy {
        val data = NetCDFCache.loadFile(file, false)!!
        val variable = data.findVariable(variableName)!!
        val image = VariableImage(variable)
        width = image.width
        height = image.height
        image
    }
    override fun getWidth() = image.width

    override fun getHeight() = image.height

    override fun getRGB(p0: Int) = image.getRGB(p0)

    override fun createBufferedImage(): BufferedImage {
        return image.createBufferedImage()
    }

    override fun createBufferedImage(dstWidth: Int, dstHeight: Int): BufferedImage {
        return image.createBufferedImage(dstWidth, dstHeight)
    }

    override fun createIntImage(): IntImage {
        return image.createIntImage()
    }

    override fun createTexture(texture: Texture2D?, checkRedundancy: Boolean) {
        image.createTexture(texture, checkRedundancy)
    }

}