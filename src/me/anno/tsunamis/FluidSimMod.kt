package me.anno.tsunamis

import me.anno.engine.RemsEngine
import me.anno.extensions.ExtensionLoader
import me.anno.extensions.mods.Mod
import me.anno.image.Image
import me.anno.image.ImageCache
import me.anno.image.colormap.LinearColorMap
import me.anno.io.Saveable.Companion.registerCustomClass
import me.anno.io.files.FileReference
import me.anno.io.files.Signature
import me.anno.io.files.inner.InnerFolderCache
import me.anno.io.utils.StringMap
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.io.NetCDFCache
import me.anno.tsunamis.io.NetCDFImageCache
import me.anno.tsunamis.setups.*
import me.anno.utils.Color.black
import me.anno.utils.structures.Callback
import java.io.InputStream

class FluidSimMod : Mod() {

    // the bytes, that identify a NetCDF file; such file signatures can be registered to identify file types within Rem's Engine
    private val netCDFSignatures = listOf(
        Signature("netcdf", 0, 0x89, 'H'.code, 'D'.code, 'F'.code),
        Signature("netcdf", 0, 'C'.code, 'D'.code, 'F'.code)
    )

    private val colorMapSignatures = listOf(
        Signature("colormap", 0, "<ColorMap")
    )

    private fun readNetCDF(file: FileReference, callback: Callback<Image>) {
        callback.call(NetCDFImageCache.getData(file, false), null)
    }

    private fun readNetCDF(bytes: ByteArray, callback: Callback<Image>) {
        callback.call(readNetCDF0(bytes), null)
    }

    private fun readNetCDF0(bytes: ByteArray): Image? {
        return NetCDFImageCache.getData(bytes, null)
    }

    private fun readNetCDF(inputStream: InputStream, callback: Callback<Image>) {
        val bytes = inputStream.use { it.readBytes() }
        callback.call(readNetCDF0(bytes), null)
    }

    private fun readColorMap(bytes: ByteArray, callback: Callback<Image>) {
        bytes.inputStream().use { callback.call(ColorMap.read(it), null) }
    }

    private fun readColorMap(file: FileReference, callback: Callback<Image>) {
        file.inputStream { it, err ->
            if (it != null) callback.call(ColorMap.read(it), null)
            else err?.printStackTrace()
        }
    }

    private fun readColorMap(inputStream: InputStream, callback: Callback<Image>) {
        inputStream.use { callback.call(ColorMap.read(it), null) }
    }

    override fun onPreInit() {
        super.onPreInit()

        // register the NetCDF file signature, and image readers for it
        for (s in netCDFSignatures) Signature.register(s)
        ImageCache.registerReader("netcdf", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        ImageCache.registerReader("nc", ::readNetCDF, ::readNetCDF, ::readNetCDF)
        InnerFolderCache.registerSignatures("netcdf", NetCDFCache::readAsFolder)

        // register color map preview
        for (s in colorMapSignatures) Signature.register(s)
        ImageCache.registerReader("colormap", ::readColorMap, ::readColorMap, ::readColorMap)

        // register components
        registerCustomClass(FluidSim())
        registerCustomClass(LinearDiscontinuitySetup())
        registerCustomClass(CircularDiscontinuitySetup())
        registerCustomClass(NetCDFSetup())
        registerCustomClass(GMTTrackSetup())
        registerCustomClass(CriticalFlowSetup())
        registerCustomClass(PoolSetup())
        registerCustomClass(ParabolaSetup())
        registerCustomClass(ManualProceduralMesh())

    }

    companion object {

        val linColorMap = LinearColorMap(0x0055ff or black, -1, 0xff0000 or black)
            .clone(-1f, 1f)

        @JvmStatic
        fun main(args: Array<String>) {
            ExtensionLoader.loadMainInfo()
            registerCustomClass(StringMap())
            RemsEngine().run()
        }
    }

}