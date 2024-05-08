package me.anno.tsunamis.perf

import me.anno.io.files.FileReference
import me.anno.io.files.InvalidRef
import me.anno.io.files.Reference.getReference
import me.anno.io.yaml.generic.YAMLNode
import me.anno.io.yaml.generic.YAMLReader
import me.anno.tsunamis.setups.*
import me.anno.utils.Sleep
import org.apache.logging.log4j.LogManager
import java.io.IOException
import kotlin.math.max

object SetupLoader {

    private val LOGGER = LogManager.getLogger(SetupLoader::class)

    data class Setup(
        val width: Int,
        val height: Int,
        val gravity: Float,
        val cellSizeMeters: Float,
        val cflFactor: Float,
        val minFluidHeight: Float,
        val setup: FluidSimSetup,
        val config: YAMLNode?
    )

    operator fun YAMLNode?.get(key: String, default: String): String {
        val v = this?.get(key) ?: return default
        return v.value ?: default
    }

    operator fun YAMLNode?.get(key: String, default: FileReference): FileReference {
        val v = this?.get(key)?.value ?: return default
        return getReference(v)
    }

    operator fun YAMLNode?.get(key: String, default: Float): Float {
        val v = this?.get(key)?.value ?: return default
        return v.toFloatOrNull() ?: default
    }

    operator fun YAMLNode?.get(key: String, default: Double): Double {
        val v = this?.get(key)?.value ?: return default
        return v.toDoubleOrNull() ?: default
    }

    operator fun YAMLNode?.get(key: String, default: Int): Int {
        val v = this?.get(key)?.value ?: return default
        return v.toIntOrNull() ?: default
    }

    operator fun YAMLNode?.get(key: String, default: Boolean): Boolean {
        val v = this?.get(key)?.value ?: return default
        return when (v.lowercase()) {
            "true", "t", "1", "yes", "y" -> true
            else -> false
        }
    }

    fun load(args: Array<String>): Setup {
        val ref = if (args.isEmpty()) InvalidRef
        else getReference(args[0])
        return load(ref)
    }

    fun load(file: FileReference): Setup {

        var setup: FluidSimSetup = NetCDFSetup()
        if (setup is NetCDFSetup) {
            val folder = getReference("E:/Documents/Uni/Master/WS2122")
            setup.bathymetryFile = folder.getChild("tohoku_gebco08_ucsb3_250m_bath.nc")
            setup.displacementFile = folder.getChild("tohoku_gebco08_ucsb3_250m_displ.nc")
        }

        var width = 100
        var height = 100

        var gravity = 9.81f
        var cfl = 0.45f

        var cellSizeMeters = 1f

        var config: YAMLNode? = null

        var scale = 1f

        var minFluidHeight = 1e-7f

        // the first param is the config
        if (file.exists && !file.isDirectory) try {

            /* sample:
            setup: Tsunami2d
            bathymetryFile: ../chile_gebco20_usgs_250m_bath.nc
            displacementFile: ../chile_gebco20_usgs_250m_displ.nc
            maxDuration: 3600
            maxSteps: 100000
            scale: 1
            outputFile: chile-250.nc
            outputStepSize: 12
            outputPeriod: 15
            * */
            config = YAMLReader.parseYAML(file.inputStreamSync().bufferedReader(), beautify = false)


            // + 2 for ghost cells
            width = max(1, config["nx", width]) + 2
            height = max(1, config["ny", height]) + 2

            gravity = config["gravity", gravity]
            cfl = config["cflFactor", cfl]

            minFluidHeight = config["minFluidHeight", minFluidHeight]

            scale = config["scale", 1f]

            when (val type = config["setup"]?.value) {
                "DamBreak1d", "DamBreak" -> {
                    setup = LinearDiscontinuitySetup()
                    setup.heightLeft = config["hl", setup.heightLeft]
                    setup.heightRight = config["hr", setup.heightRight]
                    setup.impulseLeft = config["hul", setup.impulseLeft]
                    setup.impulseRight = config["hur", setup.impulseRight]
                    setup.bathymetryLeft = config["bl", setup.bathymetryLeft]
                    setup.bathymetryRight = config["br", setup.bathymetryRight]
                }
                "DamBreak2d", "DamBreakCircle" -> {
                    // l_heightLeft, l_heightRight, l_splitPositionX, l_splitPositionY, l_damRadius, l_damBathymetry
                    setup = CircularDiscontinuitySetup()
                    setup.heightInner = config["hl", setup.heightInner]
                    setup.heightOuter = config["hr", setup.heightOuter]
                    setup.impulseInner = config["hul", setup.impulseInner]
                    setup.impulseOuter = config["hur", setup.impulseOuter]
                    // was originally absolute, now is relative
                    setup.radius = config["damRadius", setup.radius * width] / width
                }
                "Tsunami2d", "NetCDF", "NetCDFSetup" -> {
                    setup as NetCDFSetup
                    setup.bathymetryFile = config["bathymetryFile", setup.bathymetryFile]
                    setup.displacementFile = config["displacementFile", setup.displacementFile]
                }
                "ArtificialTsunami2d", "PoolSetup" -> {
                    setup = PoolSetup()
                    setup.poolDepth = config["poolDepth", setup.poolDepth]
                    setup.displacementHeight = config["displacementHeight", setup.displacementHeight]
                    setup.displacementFractionX =
                        config["displacementFractionX", setup.displacementFractionX]
                    setup.displacementFractionZ =
                        config["displacementFractionY", setup.displacementFractionZ]
                }
                "GMTTrack", "Tsunami" -> {
                    /* file format: csv
                    longitude,latitude,track_location,height
                    141.024949,37.316569,0,14.7254650696
                    141.027770389,37.31662806,250.000325724,-7.50934185448
                    141.030591782,37.316687053,500.000650342,-7.13790480308
                    141.033413179,37.316745979,750.000973849,-7.5143793363
                    */
                    val setupFile = config["setupFile", InvalidRef]
                    if (setupFile == InvalidRef) throw RuntimeException("Missing parameter setupFile for GMT track data")
                    if (!setupFile.exists) throw RuntimeException("Could not find $setupFile")
                    if (setupFile.isDirectory) throw RuntimeException("Setup file must be a file, not a directory")
                    setup = GMTTrackSetup()
                    setup.sourceFile = setupFile
                    cellSizeMeters = setup.getPreferredCellSizeMeters()
                    width = ((setup.getPreferredNumCellsX() + 2) * scale - 2).toInt() // 2 ghost cells
                    height = 1
                }
                "Critical" -> {
                    setup = CriticalFlowSetup()
                    setup.shallowDepth = config["shallowDepth", setup.shallowDepth]
                    setup.baseDepth = config["baseDepth", setup.baseDepth]
                    setup.momentumX = config["momentumX", setup.momentumX]
                    setup.bowWidth = config["bowWidth", setup.bowWidth]
                    setup.bowPower = config["bowPower", setup.bowPower]
                }
                "Subcritical", "SubcriticalFlow", "SubcriticalFlow1d" -> {
                    setup = CriticalFlowSetup()
                    setup.shallowDepth = config["shallowDepth", 1.8f]
                    setup.baseDepth = config["baseDepth", 2f]
                    setup.momentumX = config["momentumX", 4.42f]
                    setup.bowWidth = config["bowWidth", 0.2f]
                    setup.bowPower = config["bowPower", 2f]
                }
                "Supercritical", "SupercriticalFlow", "SupercriticalFlow1d" -> {
                    setup = CriticalFlowSetup()
                    setup.shallowDepth = config["shallowDepth", 0.13f]
                    setup.baseDepth = config["baseDepth", 0.33f]
                    setup.momentumX = config["momentumX", 0.18f]
                    setup.bowWidth = config["bowWidth", 0.2f]
                    setup.bowPower = config["bowPower", 2f]
                }
                // a checkpoint could be used as well...
                null -> { /* fine, use default */
                }
                else -> throw RuntimeException("Invalid setup type $type")
            }

            setup.hasBorder = config["hasBorder", setup !is NetCDFSetup]
            setup.borderHeight = config["borderHeight", setup.borderHeight]

        } catch (e: IOException) {
            LOGGER.warn("Config was not found", e)
        }

        if (setup is NetCDFSetup) {
            Sleep.waitUntil(true) { setup.isReady() }
            width = (setup.getPreferredNumCellsX() * scale).toInt()
            height = (setup.getPreferredNumCellsY() * scale).toInt()
        }

        return Setup(width, height, gravity, cellSizeMeters, cfl, minFluidHeight, setup, config)

    }

}