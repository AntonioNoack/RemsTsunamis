package me.anno.tsunamis.io

import me.anno.io.files.FileReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.utils.OS.documents
import me.anno.utils.Sleep.waitUntil
import me.anno.utils.hpc.HeavyProcessing.processBalanced
import ucar.ma2.DataType
import ucar.ma2.Index
import ucar.nc2.write.NetcdfFormatWriter
import kotlin.concurrent.thread

object NetCDFExport {

    private fun removeBorder(width: Int, height: Int, src: FloatArray, dst: FloatArray): FloatArray {
        processBalanced(0, height, false) { y0, y1 ->
            for (y in y0 until y1) {
                val srcI0 = (width + 2) * (y + 1) + 1
                val srcI1 = srcI0 + width
                val dstDelta = width * y - srcI0 // < 0
                for (srcI in srcI0 until srcI1) {
                    dst[srcI + dstDelta] = src[srcI]
                }
            }
        }
        return dst
    }

    /**
     * @param sim fluid simulation
     * @param writeGhostCells whether the ghost cells should be written
     * @param dst destination file
     * */
    fun export(sim: FluidSim, writeGhostCells: Boolean, dst: FileReference = documents.getChild("fluidSim.nc")) {

        val width = sim.width // width without ghost cells
        val height = sim.height // height without ghost cells

        // values
        // todo request these values as a large chunk of data
        val engine = sim.engine as CPUEngine
        val h = engine.fluidHeight
        val b = engine.bathymetry
        val hu = engine.fluidMomentumX
        val hv = engine.fluidMomentumY
        val setup = sim.setup!!

        // is this part thread-safe?

        thread(name = "NetCDF-Export") {

            // don't freeze the UI while saving

            val builder = NetcdfFormatWriter.builder()
                .setNewFile(true)
                .setLocation(dst.absolutePath)
            // .setFormat(NetcdfFileFormat.NETCDF4) // idk...

            val xName = "x"
            val yName = "y"

            val hName = "height"
            val bName = "bathymetry"
            val huName = "momentumX"
            val hvName = "momentumY"
            val dispName = "displacement"

            val dataType = DataType.FLOAT

            val writtenWidth = if (writeGhostCells) width + 2 else width
            val writtenHeight = if (writeGhostCells) height + 2 else height

            builder.addDimension(xName, writtenWidth)
            builder.addDimension(yName, writtenHeight)

            // each dimension is defined by the name, or an integer for its size (becomes an anonymous dimension)
            // multiple dimensions are split by space/tab/newline
            val dimString = "$yName $xName"
            builder.addVariable(bName, dataType, dimString)
            builder.addVariable(hName, dataType, dimString)
            builder.addVariable(huName, dataType, dimString)
            builder.addVariable(hvName, dataType, dimString)
            builder.addVariable(dispName, dataType, dimString)

            val shape = Index.factory(intArrayOf(writtenHeight, writtenWidth))

            val writer = builder.build() // we switch from the define to the declare stage
            // write all values
            // whole name must be given for ucar.ma2.Array, because "Array" is a default type for Kotlin
            val tmp = FloatArray(writtenWidth * writtenHeight)
            if (writeGhostCells) {
                writer.write(hName, ucar.ma2.Array.factory(dataType, shape, h))
                writer.write(bName, ucar.ma2.Array.factory(dataType, shape, b))
                writer.write(huName, ucar.ma2.Array.factory(dataType, shape, hu))
                writer.write(hvName, ucar.ma2.Array.factory(dataType, shape, hv))
            } else {
                writer.write(hName, ucar.ma2.Array.factory(dataType, shape, removeBorder(width, height, h, tmp)))
                writer.write(bName, ucar.ma2.Array.factory(dataType, shape, removeBorder(width, height, b, tmp)))
                writer.write(huName, ucar.ma2.Array.factory(dataType, shape, removeBorder(width, height, hu, tmp)))
                writer.write(hvName, ucar.ma2.Array.factory(dataType, shape, removeBorder(width, height, hv, tmp)))
            }

            waitUntil(true) { setup.isReady() }

            // fill temporary array with displacement
            val dx = if (writeGhostCells) -1 else 0
            val dy = if (writeGhostCells) -1 else 0
            processBalanced(dy, dy + writtenHeight, false) { y0, y1 ->
                for (y in y0 until y1) {
                    var i = (y - dy) * writtenWidth
                    for (x in dx until dx + writtenWidth) {
                        tmp[i++] = setup.getDisplacement(x, y, width, height)
                    }
                }
            }

            writer.write(dispName, ucar.ma2.Array.factory(dataType, shape, tmp))
            writer.close()

        }
    }

}