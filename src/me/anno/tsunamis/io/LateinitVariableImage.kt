package me.anno.tsunamis.io

import me.anno.gpu.texture.Texture2D
import me.anno.image.Image
import me.anno.image.raw.IntImage
import me.anno.io.files.FileReference
import java.awt.image.BufferedImage

class LateinitVariableImage(val variableName: String, val file: FileReference) :
    Image(1, 1, 1, false) {

    private var image: VariableImage? = null
    private fun init() {
        if (image == null) {
            synchronized(this) {
                if (image == null) {
                    val data = NetCDFCache.loadFile(file, false)!!
                    val variable = data.findVariable(variableName)!!
                    val image = VariableImage(variable)
                    width = image.width
                    height = image.height
                    this.image = image
                }
            }
        }
    }

    override fun getWidth(): Int {
        init()
        return image!!.getWidth()
    }

    override fun getHeight(): Int {
        init()
        return image!!.getHeight()
    }

    override fun getRGB(p0: Int): Int {
        init()
        return image!!.getRGB(p0)
    }

    override fun createBufferedImage(): BufferedImage {
        init()
        return image!!.createBufferedImage()
    }

    override fun createBufferedImage(dstWidth: Int, dstHeight: Int): BufferedImage {
        init()
        return image!!.createBufferedImage(dstWidth, dstHeight)
    }

    override fun createIntImage(): IntImage {
        init()
        return image!!.createIntImage()
    }

    override fun createRGBFrom3StridedData(texture: Texture2D?, checkRedundancy: Boolean, data: ByteArray?) {
        init()
        image!!.createRGBFrom3StridedData(texture, checkRedundancy, data)
    }

    override fun createTexture(texture: Texture2D?, checkRedundancy: Boolean) {
        init()
        image!!.createTexture(texture, checkRedundancy)
    }

}