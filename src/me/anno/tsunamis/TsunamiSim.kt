package me.anno.tsunamis

import me.anno.engine.RemsEngine
import me.anno.extensions.ExtensionLoader
import me.anno.extensions.mods.Mod
import me.anno.image.Image
import me.anno.image.ImageCPUCache
import me.anno.io.ISaveable.Companion.registerCustomClass
import me.anno.io.files.FileReference
import me.anno.io.files.Signature
import me.anno.io.zip.ZipCache
import me.anno.tsunamis.io.NetCDFCache
import me.anno.tsunamis.io.NetCDFImageCache
import me.anno.tsunamis.setups.*
import java.io.InputStream

class TsunamiSim : Mod() {

    // the bytes, that identify a NetCDF file; such file signatures can be registered to identify file types within Rem's Engine
    private val netCDFSignature = Signature("netcdf", 0, listOf(0x89, 'H'.code, 'D'.code, 'F'.code))

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

    override fun onPreInit() {
        super.onPreInit()

        // register the NetCDF file signature, and image readers for it
        Signature.register(netCDFSignature)
        ImageCPUCache.registerReader("netcdf", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        ImageCPUCache.registerReader("nc", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        ZipCache.register("netcdf", NetCDFCache::readAsFolder)

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

        Signature.unregister(netCDFSignature)

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ExtensionLoader.loadMainInfo()
            RemsEngine().run()
        }
    }

}