package me.anno.tsunamis.io

import me.anno.cache.CacheSection
import me.anno.io.files.FileReference
import me.anno.io.files.InvalidRef
import me.anno.tsunamis.io.NetCDFCache.loadFile
import ucar.nc2.NetcdfFile
import ucar.nc2.NetcdfFiles

object NetCDFImageCache : CacheSection("NetCDF-Images") {

    fun getData(bytes: ByteArray, variableName: String?): VariableImage? {
        synchronized(NetCDFMutex) {
            val data = NetcdfFiles.openInMemory("?", bytes)
            val image = getData(data, variableName)
            data.close()
            return image
        }
    }

    fun getData(file: FileReference, async: Boolean): VariableImage? {
        return if (file.isDirectory) {
            // we have specified the variable name like a folder
            getData(file.getParent() ?: InvalidRef, file.name, async)
        } else {
            // we don't have specified it
            getData(file, null, async)
        }
    }

    fun getData(file: FileReference, variableName: String?, async: Boolean): VariableImage? {
        if (!file.exists || file.isDirectory) return null
        return getEntry(file, file.lastModified, 1000, async) { file1, _ ->
            val data = loadFile(file1, false)
            if (data == null) null else getData(data, variableName)
        } as? VariableImage
    }

    private fun getData(data: NetcdfFile, variableName: String?): VariableImage? {
        var variable = if (variableName != null) data.findVariable(variableName) else null
        if (variable == null) {
            variable = data.variables.firstOrNull {
                it.shape.size in 1..2 && !it.isCoordinateVariable && !it.isMetadata && !it.isUnlimited
            } ?: return null
        }
        return VariableImage(variable)
    }

}