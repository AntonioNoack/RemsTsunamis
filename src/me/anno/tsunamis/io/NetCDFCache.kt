package me.anno.tsunamis.io

import me.anno.cache.CacheData
import me.anno.cache.CacheSection
import me.anno.io.files.FileFileRef
import me.anno.io.files.FileReference
import me.anno.io.zip.InnerFolder
import ucar.nc2.NetcdfFile
import ucar.nc2.NetcdfFiles
import java.io.IOException

object NetCDFCache : CacheSection("NetCDF") {

    fun loadFile(file: FileReference, async: Boolean): NetcdfFile? {
        val data = getFileEntry(file, false, 10_000, async) { file1, _ ->
            synchronized(NetCDFMutex) {
                val netcdfFile = if (file1 is FileFileRef) {
                    NetcdfFiles.open(file1.absolutePath)
                } else {
                    NetcdfFiles.openInMemory(file1.name, file1.readBytes())
                }
                object : CacheData<NetcdfFile>(netcdfFile) {
                    override fun destroy() {
                        value.close()
                    }
                }
            }
        } as? CacheData<*>
        return data?.value as? NetcdfFile
    }

    fun readAsFolder(file: FileReference): InnerFolder {
        val folder = InnerFolder(file)
        val data = loadFile(file, false) ?: throw IOException("Could not read $file as NetCDF file")
        for (variable in data.variables) {
            var name = variable.fullName
            if (!(name.endsWith(".jpg") || name.endsWith(".png"))) name = "$name.png"
            folder.createImageChild(name, LateinitVariableImage(variable.fullName, file))
        }
        // we could convert most meta data into structures and images
        return folder
    }

}