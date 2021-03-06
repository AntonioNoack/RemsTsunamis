package me.anno.tsunamis.perf

import me.anno.io.files.FileReference
import me.anno.io.files.InvalidRef
import me.anno.io.yaml.YAMLNode
import me.anno.io.yaml.YAMLReader
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

    fun YAMLNode?.getOrDefault(key: String, default: String): String {
        this ?: return default
        val v = this[key] ?: return default
        return v.value ?: default
    }

    fun YAMLNode?.getOrDefault(key: String, default: FileReference): FileReference {
        this ?: return default
        val v = this[key]?.value ?: return default
        return FileReference.Companion.getReference(v)
    }

    fun YAMLNode?.getOrDefault(key: String, default: Float): Float {
        this ?: return default
        val v = this[key]?.value ?: return default
        return v.toFloatOrNull() ?: default
    }

    fun YAMLNode?.getOrDefault(key: String, default: Double): Double {
        this ?: return default
        val v = this[key]?.value ?: return default
        return v.toDoubleOrNull() ?: default
    }

    fun YAMLNode?.getOrDefault(key: String, default: Int): Int {
        this ?: return default
        val v = this[key]?.value ?: return default
        return v.toIntOrNull() ?: default
    }

    fun YAMLNode?.getOrDefault(key: String, default: Boolean): Boolean {
        this ?: return default
        val v = this[key]?.value ?: return default
        return when (v.lowercase()) {
            "true", "t", "1", "yes", "y" -> true
            else -> false
        }
    }

    fun load(args: Array<String>): Setup {
        val ref = if (args.isEmpty()) InvalidRef
        else FileReference.Companion.getReference(args[0])
        return load(ref)
    }

    fun load(file: FileReference): Setup {

        var setup: FluidSimSetup = NetCDFSetup()
        if (setup is NetCDFSetup) {
            val folder = FileReference.getReference("E:/Documents/Uni/Master/WS2122")
            setup.bathymetryFile = FileReference.getReference(folder, "tohoku_gebco08_ucsb3_250m_bath.nc")
            setup.displacementFile = FileReference.getReference(folder, "tohoku_gebco08_ucsb3_250m_displ.nc")
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
            config = YAMLReader.parseYAML(file, beautify = false)


            // + 2 for ghost cells
            width = max(1, config.getOrDefault("nx", width)) + 2
            height = max(1, config.getOrDefault("ny", height)) + 2

            gravity = config.getOrDefault("gravity", gravity)
            cfl = config.getOrDefault("cflFactor", cfl)

            minFluidHeight = config.getOrDefault("minFluidHeight", minFluidHeight)

            scale = config.getOrDefault("scale", 1f)

            when (val type = config["setup"]?.value) {
                "DamBreak1d", "DamBreak" -> {
                    setup = LinearDiscontinuitySetup()
                    setup.heightLeft = config.getOrDefault("hl", setup.heightLeft)
                    setup.heightRight = config.getOrDefault("hr", setup.heightRight)
                    setup.impulseLeft = config.getOrDefault("hul", setup.impulseLeft)
                    setup.impulseRight = config.getOrDefault("hur", setup.impulseRight)
                    setup.bathymetryLeft = config.getOrDefault("bl", setup.bathymetryLeft)
                    setup.bathymetryRight = config.getOrDefault("br", setup.bathymetryRight)
                }
                "DamBreak2d", "DamBreakCircle" -> {
                    // l_heightLeft, l_heightRight, l_splitPositionX, l_splitPositionY, l_damRadius, l_damBathymetry
                    setup = CircularDiscontinuitySetup()
                    setup.heightInner = config.getOrDefault("hl", setup.heightInner)
                    setup.heightOuter = config.getOrDefault("hr", setup.heightOuter)
                    setup.impulseInner = config.getOrDefault("hul", setup.impulseInner)
                    setup.impulseOuter = config.getOrDefault("hur", setup.impulseOuter)
                    // was originally absolute, now is relative
                    setup.radius = config.getOrDefault("damRadius", setup.radius * width) / width
                }
                "Tsunami2d", "NetCDF", "NetCDFSetup" -> {
                    setup as NetCDFSetup
                    setup.bathymetryFile = config.getOrDefault("bathymetryFile", setup.bathymetryFile)
                    setup.displacementFile = config.getOrDefault("displacementFile", setup.displacementFile)
                }
                "ArtificialTsunami2d", "PoolSetup" -> {
                    setup = PoolSetup()
                    setup.poolDepth = config.getOrDefault("poolDepth", setup.poolDepth)
                    setup.displacementHeight = config.getOrDefault("displacementHeight", setup.displacementHeight)
                    setup.displacementFractionX =
                        config.getOrDefault("displacementFractionX", setup.displacementFractionX)
                    setup.displacementFractionZ =
                        config.getOrDefault("displacementFractionY", setup.displacementFractionZ)
                }
                "GMTTrack", "Tsunami" -> {
                    /* file format: csv
                    longitude,latitude,track_location,height
                    141.024949,37.316569,0,14.7254650696
                    141.027770389,37.31662806,250.000325724,-7.50934185448
                    141.030591782,37.316687053,500.000650342,-7.13790480308
                    141.033413179,37.316745979,750.000973849,-7.5143793363
                    */
                    val setupFile = config.getOrDefault("setupFile", InvalidRef)
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
                    setup.shallowDepth = config.getOrDefault("shallowDepth", setup.shallowDepth)
                    setup.baseDepth = config.getOrDefault("baseDepth", setup.baseDepth)
                    setup.momentumX = config.getOrDefault("momentumX", setup.momentumX)
                    setup.bowWidth = config.getOrDefault("bowWidth", setup.bowWidth)
                    setup.bowPower = config.getOrDefault("bowPower", setup.bowPower)
                }
                "Subcritical", "SubcriticalFlow", "SubcriticalFlow1d" -> {
                    setup = CriticalFlowSetup()
                    setup.shallowDepth = config.getOrDefault("shallowDepth", 1.8f)
                    setup.baseDepth = config.getOrDefault("baseDepth", 2f)
                    setup.momentumX = config.getOrDefault("momentumX", 4.42f)
                    setup.bowWidth = config.getOrDefault("bowWidth", 0.2f)
                    setup.bowPower = config.getOrDefault("bowPower", 2f)
                }
                "Supercritical", "SupercriticalFlow", "SupercriticalFlow1d" -> {setup = CriticalFlowSetup()
                    setup.shallowDepth = config.getOrDefault("shallowDepth", 0.13f)
                    setup.baseDepth = config.getOrDefault("baseDepth", 0.33f)
                    setup.momentumX = config.getOrDefault("momentumX", 0.18f)
                    setup.bowWidth = config.getOrDefault("bowWidth", 0.2f)
                    setup.bowPower = config.getOrDefault("bowPower", 2f)
                }
                // a checkpoint could be used as well...
                null -> { /* fine, use default */
                }
                else -> throw RuntimeException("Invalid setup type $type")
            }

            setup.hasBorder = config.getOrDefault("hasBorder", setup !is NetCDFSetup)
            setup.borderHeight = config.getOrDefault("borderHeight", setup.borderHeight)

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