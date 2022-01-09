package me.anno.tsunamis.io

import me.anno.io.files.FileReference
import me.anno.utils.Clock
import me.anno.utils.OS.desktop
import javax.imageio.ImageIO

fun main() {
    val c = Clock()
    val src = FileReference.getReference("E:\\Documents\\Uni\\Master\\WS2122/tohoku_gebco08_ucsb3_250m_bath.nc")
    c.stop("getReference")
    val inMemory = src.readBytes()
    c.stop("inMemory")
    // NetCDFCache.loadFile(src, false)?.close()
    c.stop("Test")
    val image = NetCDFImageCache.getData(inMemory, null)
    c.stop("Loading Data")
    val bi = image!!.createBufferedImage(64, 64)
    c.stop("Downsampling")
    desktop.getChild("fluid.png").outputStream().use {
        ImageIO.write(bi, "png", it)
    }
    c.stop("Writing Result")
    c.total("Total")
}