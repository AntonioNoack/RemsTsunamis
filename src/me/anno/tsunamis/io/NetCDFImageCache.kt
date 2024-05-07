package me.anno.tsunamis.io

import me.anno.Engine
import me.anno.Time
import me.anno.cache.CacheSection
import me.anno.io.files.FileReference
import me.anno.io.files.InvalidRef
import me.anno.tsunamis.io.NetCDFCache.loadFile
import me.anno.utils.Clock
import ucar.nc2.NetcdfFile
import ucar.nc2.NetcdfFiles

object NetCDFImageCache : CacheSection("NetCDF-Images") {

    fun getData(bytes: ByteArray, variableName: String?): VariableImage? {
        synchronized(NetCDFMutex) {
            val data = NetcdfFiles.openInMemory(Time.nanoTime.toString(), bytes)
            val image = getData(data, variableName)
            data.close()
            return image
        }
    }

    fun getData(file: FileReference, async: Boolean): VariableImage? {
        return if (file.isDirectory) {
            // we have specified the variable name like a folder
            getData(file.getParent(), file.name, async)
        } else {
            // we don't have specified it
            getData(file, null, async)
        }
    }

    fun getData(file: FileReference, variableName: String?, async: Boolean): VariableImage? {
        return getFileEntry(file, false, 30_000, async) { file1, _ ->
            synchronized(NetCDFMutex) {
                val c = Clock()
                val data = loadFile(file1, false)
                c.stop("Loading File")
                val image = getData(data, variableName)
                c.stop("Getting Data")
                image
            }
        } as? VariableImage
    }

    private fun getData(data: NetcdfFile?, variableName: String?): VariableImage? {
        data ?: return null
        synchronized(NetCDFMutex) {
            val c = Clock()
            var variable = if (variableName != null) data.findVariable(variableName) else null
            if (variable == null) {
                variable = data.variables.firstOrNull {
                    it.shape.size in 1..2 && !it.isCoordinateVariable && !it.isMetadata && !it.isUnlimited
                } ?: return null
            }
            c.stop("Finding Variable")
            return try {
                val image = VariableImage(variable)
                c.stop("Creating Variable Image")
                image
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

}