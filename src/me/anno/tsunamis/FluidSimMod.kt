package me.anno.tsunamis

import me.anno.engine.RemsEngine
import me.anno.extensions.ExtensionLoader
import me.anno.extensions.mods.Mod
import me.anno.gpu.shader.OpenGLShader
import me.anno.image.Image
import me.anno.image.ImageCPUCache
import me.anno.io.ISaveable.Companion.registerCustomClass
import me.anno.io.files.FileReference
import me.anno.io.files.Signature
import me.anno.io.zip.ZipCache
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.io.NetCDFCache
import me.anno.tsunamis.io.NetCDFImageCache
import me.anno.tsunamis.setups.*
import java.io.InputStream

class FluidSimMod : Mod() {

    // the bytes, that identify a NetCDF file; such file signatures can be registered to identify file types within Rem's Engine
    private val netCDFSignatures = listOf(
        Signature("netcdf", 0, listOf(0x89, 'H'.code, 'D'.code, 'F'.code)),
        Signature("netcdf", 0, listOf('C'.code, 'D'.code, 'F'.code))
    )

    private val colorMapSignatures = listOf(
        Signature("colormap", 0, "<ColorMap")
    )

    private fun readNetCDF(file: FileReference): Image? {
        return NetCDFImageCache.getData(file, false)
    }

    private fun readNetCDF(bytes: ByteArray): Image? {
        return NetCDFImageCache.getData(bytes, null)
    }

    private fun readNetCDF(inputStream: InputStream): Image? {
        val bytes = inputStream.readBytes()
        inputStream.close()
        return readNetCDF(bytes)
    }

    private fun readColorMap(bytes: ByteArray): Image? {
        return bytes.inputStream().use { ColorMap.read(it) }
    }

    private fun readColorMap(file: FileReference): Image? {
        return file.inputStream().use { ColorMap.read(it) }
    }

    private fun readColorMap(inputStream: InputStream): Image? {
        return inputStream.use { ColorMap.read(it) }
    }

    override fun onPreInit() {
        super.onPreInit()

        // register the NetCDF file signature, and image readers for it
        for (s in netCDFSignatures)
            Signature.register(s)
        ImageCPUCache.registerReader("netcdf", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        ImageCPUCache.registerReader("nc", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        ZipCache.register("netcdf", NetCDFCache::readAsFolder)

        // register color map preview
        for (s in colorMapSignatures)
            Signature.register(s)
        ImageCPUCache.registerReader("colormap", ::readColorMap, ::readColorMap, ::readColorMap)

        // register components
        registerCustomClass(FluidSim())
        registerCustomClass(LinearDiscontinuity())
        registerCustomClass(CircularDiscontinuity())
        registerCustomClass(NetCDFSetup())
        registerCustomClass(CriticalFlowSetup())
        registerCustomClass(PoolSetup())

    }

    override fun onExit() {
        super.onExit()
        for (signature in netCDFSignatures)
            Signature.unregister(signature)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ExtensionLoader.loadMainInfo()
            // print all shaders
            // OpenGLShader.logShaders = true
            RemsEngine().run()
        }
    }

}