package me.anno.tsunamis.setups

import me.anno.ecs.annotations.DebugProperty
import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.serialization.SerializedProperty
import me.anno.tsunamis.FluidSim.Companion.threadPool
import me.anno.tsunamis.io.NetCDFImageCache.getData
import me.anno.tsunamis.io.VariableImage
import me.anno.utils.hpc.HeavyProcessing.processBalanced

class NetCDFSetup : FluidSimSetup() {

    @SerializedProperty
    var bathymetryFile: FileReference =
        getReference("E:\\Documents\\Uni\\Master\\WS2122/tohoku_gebco08_ucsb3_250m_bath.nc")

    @SerializedProperty
    var displacementFile: FileReference =
        getReference("E:\\Documents\\Uni\\Master\\WS2122/tohoku_gebco08_ucsb3_250m_displ.nc")

    @Range(0.0, Double.POSITIVE_INFINITY)
    @SerializedProperty
    var shoreCliffHeight = 20f

    var applyInterpolation = true
    var applyAveraging = true

    @DebugProperty
    @Suppress("UNUSED")
    val dataWidth
        get() = getData(bathymetryFile, true)?.width ?: -1

    @DebugProperty
    @Suppress("UNUSED")
    val dataHeight
        get() = getData(bathymetryFile, true)?.height ?: -1

    override fun getPreferredNumCellsX(): Int {
        return getData(bathymetryFile, true)!!.getWidth()
    }

    override fun getPreferredNumCellsY(): Int {
        return getData(bathymetryFile, true)!!.getHeight()
    }

    override fun isReady(): Boolean {
        return getData(bathymetryFile, true) != null && getData(displacementFile, true) != null
    }

    override fun fillMomentumX(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        return -getBathymetry(x, y, w, h)
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        val data = getData(bathymetryFile, false)!!
        val fx = x.toFloat() * data.width.toFloat() / w.toFloat()
        val fy = y.toFloat() * data.height.toFloat() / h.toFloat()
        return data.getValue(fx, fy)
    }

    override fun getDisplacement(x: Int, y: Int, w: Int, h: Int): Float {
        val data = getData(displacementFile, false)!!
        val fx = x.toFloat() * data.width.toFloat() / w.toFloat()
        val fy = y.toFloat() * data.height.toFloat() / h.toFloat()
        return data.getValue(fx, fy)
    }

    override fun fillHeight(w: Int, h: Int, dst: FloatArray) {
        val data = getData(bathymetryFile, false)!!
        fillData(w, h, dst, data)
        val h0 = -shoreCliffHeight
        for (i in dst.indices) {
            val bathymetry = dst[i]
            dst[i] = when {
                bathymetry >= 0f -> 0f // beach / land
                bathymetry > h0 -> h0 // cliff zone
                else -> -bathymetry // ocean
            }
        }
    }

    override fun fillBathymetry(w: Int, h: Int, dst: FloatArray) {
        val bathymetryValues = getData(bathymetryFile, false)!!
        val displacement = getData(displacementFile, false)!!
        fillData(w, h, dst, bathymetryValues)
        val h1 = shoreCliffHeight
        if (h1 > 0f) {
            val h0 = -h1
            for (i in dst.indices) {
                val bathymetry = dst[i]
                dst[i] = when {
                    bathymetry <= h0 || bathymetry >= h1 -> bathymetry
                    bathymetry <= 0f -> h0
                    else -> h1
                }
            }
        }
        addData(w, h, dst, displacement)
    }

    private fun fillData(w: Int, h: Int, dst: FloatArray, data: VariableImage) {
        dst.fill(0f)
        addData(w, h, dst, data)
    }

    private fun addData(w: Int, h: Int, dst: FloatArray, variable: VariableImage) {
        // add variable data
        val ow = variable.width
        val oh = variable.height
        val data = variable.data
        if (ow == w + 2 && oh == h + 2) {
            // just copy the data
            processBalanced(0, w * h, false) { i0, i1 ->
                for (i in i0 until i1) {
                    dst[i] += data[i]
                }
            }
        } else {
            // for even better results, we could use averaging + interpolation on the borders
            // however, this is not really required, because we have full control over the simulated area size
            val originalIsLarger = ow > w && oh > h
            if (originalIsLarger && applyAveraging) {
                // averaging
                threadPool.processBalanced(0, h, true) { y0Out, y1Out ->
                    for (y in y0Out until y1Out) {
                        var x0In = 0
                        val y0In = y * oh / h
                        val y1In = (y + 1) * oh / h
                        var i = (y + 1) * (w + 2)
                        for (x in 0 until w) {
                            val x1In = (x + 1) * ow / w
                            var sum = 0f
                            for (yi in y0In until y1In) {
                                var j = x0In + y0In * ow
                                for (xi in x0In until x1In) {
                                    sum += data[j++]
                                }
                            }
                            val numSamples = (y1In - y0In) * (x1In - x0In)
                            dst[i++] += sum / numSamples
                            x0In = x1In
                        }
                    }
                }
            } else if (!originalIsLarger && applyInterpolation) {
                val sx = ow.toFloat() / w
                val sy = oh.toFloat() / h
                threadPool.processBalanced(0, h, true) { y0, y1 ->
                    for (y in y0 until y1) {
                        var i = (y + 1) * (w + 2)
                        for (x in 0 until w) {
                            dst[i++] += variable.getValue(x * sx, y * sy)
                        }
                    }
                }
            } else {
                // just read the values from the correct positions
                threadPool.processBalanced(0, h, true) { y0, y1 ->
                    for (y in y0 until y1) {
                        var i = (y + 1) * (w + 2)
                        for (x in 0 until w) {
                            dst[i++] += variable.getValue(x * ow / w, y * oh / h)
                        }
                    }
                }
            }
        }
    }

    override fun clone(): NetCDFSetup {
        val clone = NetCDFSetup()
        copy(clone)
        return clone
    }

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as NetCDFSetup
        clone.bathymetryFile = bathymetryFile
        clone.displacementFile = displacementFile
        clone.shoreCliffHeight = shoreCliffHeight
        clone.applyAveraging = applyAveraging
        clone.applyInterpolation = applyInterpolation
    }

    override val className: String = "Tsunamis/NetCDFSetup"

}