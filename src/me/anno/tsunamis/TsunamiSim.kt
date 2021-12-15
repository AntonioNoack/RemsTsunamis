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
import me.anno.tsunamis.setups.CircularDiscontinuity
import me.anno.tsunamis.setups.CriticalFlowSetup
import me.anno.tsunamis.setups.LinearDiscontinuity
import me.anno.tsunamis.setups.NetCDFSetup
import java.io.InputStream

class TsunamiSim : Mod() {

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