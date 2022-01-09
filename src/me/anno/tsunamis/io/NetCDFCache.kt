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
        // there is global state in NetCDF, so we cannot reuse old files :/
        /*synchronized(NetCDFMutex) {
            return if (file is FileFileRef) {
                NetcdfFiles.open(file.absolutePath)
            } else {
                NetcdfFiles.openInMemory(System.nanoTime().toString(), file.readBytes())
            }
        }*/
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
        synchronized(NetCDFMutex) {
            val folder = InnerFolder(file)
            val data = loadFile(file, false) ?: throw IOException("Could not read $file as NetCDF file")
            for (variable in data.variables) {
                var name = variable.fullName
                if (variable.shape.all { it == 1 }) {
                    // a scalar -> just write it to a text file for debugging; can't be used as an image anyways
                    val j1d = variable.read().getFloat(0)
                    folder.createTextChild("$name.txt", j1d.toString())
                } else {
                    if (!(name.endsWith(".jpg") || name.endsWith(".png"))) name = "$name.png"
                    folder.createImageChild(name, VariableImage(variable))
                }
            }
            // we could convert most meta data into structures and images
            return folder
        }
    }

}