package me.anno.tsunamis.io

import me.anno.Engine
import me.anno.engine.OfficialExtensions
import me.anno.io.files.Reference.getReference
import me.anno.utils.Clock
import me.anno.utils.OS.desktop

fun main() {
    OfficialExtensions.initForTests()
    val c = Clock("TestReadAsImage")
    val src = getReference("E:/Documents/Uni/Master/WS2122/tohoku_gebco08_ucsb3_250m_bath.nc")
    c.stop("getReference")
    val inMemory = src.readBytesSync()
    c.stop("inMemory")
    // NetCDFCache.loadFile(src, false)?.close()
    c.stop("Test")
    val image = NetCDFImageCache.getData(inMemory, null)
    c.stop("Loading Data")
    val bi = image!!.resized(64, 64, false)
    c.stop("Downsampling")
    bi.write(desktop.getChild("fluid.png"))
    c.stop("Writing Result")
    c.total("Total")
    Engine.requestShutdown()
}