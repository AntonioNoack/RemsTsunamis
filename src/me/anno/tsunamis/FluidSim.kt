package me.anno.tsunamis

import me.anno.ecs.annotations.*
import me.anno.ecs.components.mesh.ManualProceduralMesh
import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.ecs.components.mesh.terrain.TerrainUtils
import me.anno.ecs.components.mesh.terrain.TerrainUtils.generateRegularQuadHeightMesh
import me.anno.ecs.interfaces.CustomEditMode
import me.anno.ecs.prefab.Prefab
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.engine.raycast.Raycast
import me.anno.engine.ui.render.RenderView
import me.anno.gpu.GFX
import me.anno.input.Input
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.serialization.NotSerializedProperty
import me.anno.io.serialization.SerializedProperty
import me.anno.io.zip.InnerTmpFile
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.io.NetCDFExport
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.ui.editor.PropertyInspector
import me.anno.utils.hpc.HeavyProcessing.processBalanced
import me.anno.utils.hpc.ProcessingGroup
import me.anno.utils.hpc.ThreadLocal2
import me.anno.utils.maths.Maths.ceilDiv
import me.anno.utils.maths.Maths.clamp
import me.anno.utils.maths.Maths.length
import me.anno.utils.maths.Maths.mixARGB
import org.apache.logging.log4j.LogManager
import org.joml.Matrix4x3d
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * simple 3d mesh, which simulates water
 * todo stations? could print their report maybe
 * */
@ExecuteInEditMode
class FluidSim : ProceduralMesh, CustomEditMode {

    constructor()

    constructor(base: FluidSim) {
        base.copy(this)
    }

    @Group("Visuals")
    @Type("ManualProceduralMesh/PrefabSaveable")
    @SerializedProperty
    var bathymetryMesh: ManualProceduralMesh? = null

    @Group("Visuals")
    @SerializedProperty
    var colorMap: FileReference = defaultColorMap

    @Group("Visuals")
    @SerializedProperty
    var visualization = Visualisation.HEIGHT_MAP

    @Group("Size")
    @Order(0)
    @SerializedProperty
    var width = 10

    @Group("Size")
    @Order(1)
    @SerializedProperty
    var height = 10

    @Group("Size")
    @Order(2)
    @Range(1.0, 1000.0)
    @SerializedProperty
    var coarsening = 1
        set(value) {
            if (field != value && value >= 1) {
                field = value
                invalidateMesh()
                invalidateBathymetryMesh()
            }
        }

    @Docs("How large each cell is in meters; only square cells are supported")
    @Group("Size")
    @Range(0.0, Double.POSITIVE_INFINITY)
    @SerializedProperty
    var cellSizeMeters = 1f
        set(value) {
            if (field != value && value > 0f) {
                field = value
                invalidateMesh()
                invalidateBathymetryMesh()
            }
        }

    @DebugProperty
    @NotSerializedProperty
    var maxTimeStep = 0f

    @DebugProperty
    @NotSerializedProperty
    var maxVisualizedValue = 0f

    @NotSerializedProperty
    var fluidHeight: FloatArray = f0

    @NotSerializedProperty
    private var tmpH = f0

    @NotSerializedProperty
    var fluidMomentumX: FloatArray = f0

    @NotSerializedProperty
    private var tmpHuX = f0

    @NotSerializedProperty
    var fluidMomentumY: FloatArray = f0

    @NotSerializedProperty
    private var tmpHuY = f0

    @NotSerializedProperty
    var bathymetry: FloatArray = f0

    @Group("Time")
    @Docs("Gravity in m/s²")
    @SerializedProperty
    var gravity = 9.81f

    @Group("Time")
    @SerializedProperty
    var timeFactor = 1f

    @Group("Time")
    @SerializedProperty
    var isPaused = false

    @Group("Time")
    @SerializedProperty
    @Range(0.0, 0.5)
    var cfl2d = 0.45f

    @Group("Time")
    @Docs("Every n-th time step, the maximum dt is computed")
    @SerializedProperty
    var computeTimeStepInterval = 0

    @NotSerializedProperty
    var timeStepIndex = 0

    private val stride get() = width + 2

    @NotSerializedProperty
    var wantsReset = false

    @DebugAction
    @Suppress("UNUSED")
    fun resetSimulation() {
        wantsReset = true
        hasValidBathymetryMesh = false
    }

    @DebugAction
    @Suppress("UNUSED")
    fun setFluidZero() {
        val fluid = fluidHeight
        val bath = bathymetry
        if (fluid.size == bath.size) {
            for (i in fluid.indices) {
                fluid[i] = max(0f, -bath[i])
            }
            fluidMomentumX.fill(0f)
            fluidMomentumY.fill(0f)
        }
    }

    @DebugAction
    @Suppress("UNUSED")
    fun exportNetCDF() {
        // export the current state as NetCDF
        // todo we could ask the user to enter a path
        if (ensureFieldSize()) {
            NetCDFExport.export(this, false)
            isPaused = true
            // update the ui to reflect that the simulation was stopped
            PropertyInspector.invalidateUI()
        } else LOGGER.warn("Cannot initialize field")
    }

    @DebugAction
    fun invalidateBathymetryMesh() {
        hasValidBathymetryMesh = false
    }

    @NotSerializedProperty
    var hasValidBathymetryMesh = false

    fun getIndex(x: Int, y: Int): Int {
        val width = width
        val height = height
        val lx = clamp(x + 1, 0, width + 1)
        val ly = clamp(y + 1, 0, height + 1)
        return lx + ly * (width + 2)
    }

    @Suppress("UNUSED")
    fun getSurfaceHeightAt(x: Int, y: Int): Float {
        val index = getIndex(x, y)
        return fluidHeight[index] + bathymetry[index]
    }

    @Suppress("UNUSED")
    fun getFluidHeightAt(x: Int, y: Int): Float {
        return fluidHeight[getIndex(x, y)]
    }

    @Suppress("UNUSED")
    fun getMomentumXAt(x: Int, y: Int): Float {
        return fluidMomentumX[getIndex(x, y)]
    }

    @Suppress("UNUSED")
    fun getMomentumYAt(x: Int, y: Int): Float {
        return fluidMomentumY[getIndex(x, y)]
    }

    @Suppress("UNUSED")
    fun getBathymetryAt(x: Int, y: Int): Float {
        return bathymetry[getIndex(x, y)]
    }

    // initialize using setups, just like in the Tsunami Lab module
    @Type("FluidSimSetup/PrefabSaveable")
    var setup: FluidSimSetup? = null
        set(value) {
            if (field !== value) {
                field = value
                invalidateMesh()
                invalidateBathymetryMesh()
            }
        }

    @DebugAction
    fun setSizeBySetup() {
        val setup = setup
        if (setup != null) {
            if (setup.isReady()) {
                width = setup.getPreferredNumCellsX()
                height = setup.getPreferredNumCellsY()
                wantsReset = true
            } else LOGGER.warn("Setup isn't ready yet")
        } else LOGGER.warn("No setup was found!")
    }

    fun ensureFieldSize(): Boolean {
        val w = width
        val h = height
        val targetSize = (w + 2) * (h + 2)
        return if (fluidHeight.size != targetSize || wantsReset) {
            val setup = setup ?: return false
            if (!setup.isReady()) return false
            wantsReset = false
            hasValidBathymetryMesh = false
            fluidHeight = FloatArray(targetSize)
            fluidMomentumX = FloatArray(targetSize)
            fluidMomentumY = FloatArray(targetSize)
            tmpH = FloatArray(targetSize)
            tmpHuX = FloatArray(targetSize)
            tmpHuY = FloatArray(targetSize)
            bathymetry = FloatArray(targetSize)
            setup.fillHeight(w, h, fluidHeight)
            setup.fillBathymetry(w, h, bathymetry)
            setup.fillMomentumX(w, h, fluidMomentumX)
            setup.fillMomentumY(w, h, fluidMomentumY)
            if (setup.hasBorder) {
                setup.applyBorder(w, h, fluidHeight, bathymetry, fluidMomentumX, fluidMomentumY)
            }
            timeStepIndex = 0
            maxTimeStep = computeMaxTimeStep()
            true
        } else true
    }

    fun initWithSetup(setup: FluidSimSetup) {
        this.setup = setup
        if (ensureFieldSize()) {
            val w = width
            val h = height
            setup.fillHeight(w, h, fluidHeight)
            setup.fillBathymetry(w, h, bathymetry)
            setup.fillMomentumX(w, h, fluidMomentumX)
            setup.fillMomentumY(w, h, fluidMomentumY)
            setGhostOutflow(w, h, bathymetry)
        }
    }

    @NotSerializedProperty
    var computingThread: Thread? = null

    override fun onUpdate(): Int {
        if (ensureFieldSize() && !isPaused) {
            val dt = GFX.deltaTime * timeFactor
            if (dt > 0f) {
                if (computingThread == null) {
                    computingThread = thread {
                        try {
                            step(dt)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        GFX.addGPUTask(1) {
                            invalidateMesh()
                            computingThread = null
                            // why is this function not called automatically?
                            ensureBuffer()
                        }
                    }
                }
            }
            // invalidateMesh()
            // why is this function not called automatically?
            // if (GFX.isGFXThread()) ensureBuffer()
        }
        return 1 // update every tick
    }

    override fun generateMesh() {
        if (ensureFieldSize()) {
            mesh2.hasHighPrecisionNormals = true
            val width = width
            val height = height
            if (fluidHeight.size == (width + 2) * (height + 2)) {
                val bm = bathymetryMesh
                if (!hasValidBathymetryMesh && bm != null) generateBathymetryMesh(bm)
                generateFluidMesh(this)
            } // else wait for simulation
        }
    }

    fun coarseIndexToFine(w: Int, h: Int, cw: Int, scale: Int, i: Int): Int {
        val x = i % cw
        val y = i / cw
        val nx = x * scale
        val ny = min(y * scale, h)
        return nx + ny * w
    }

    private fun generateBathymetryMesh(mesh: ProceduralMesh) {
        hasValidBathymetryMesh = true
        val bathymetry = bathymetry
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.read(colorMap, false)
        val scale = max(1, coarsening)
        val w = width + 2
        val h = height + 2
        val cw = ceilDiv(w, scale)
        val ch = ceilDiv(h, scale)
        generateRegularQuadHeightMesh(
            cw, ch, 0,
            cw, cellSizeMeters * w.toFloat() / cw.toFloat(), mesh.mesh2,
            object : TerrainUtils.HeightMap {
                override fun get(it: Int): Float {
                    val i = coarseIndexToFine(w, h, cw, scale, it)
                    return bathymetry[i]
                }
            },
            object : TerrainUtils.NormalMap {
                override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                    val cx = x * scale
                    val cy = y * scale
                    dst.x = getBathymetryAt(cx + scale, cy) - getBathymetryAt(cx - scale, cy)
                    dst.y = cellSizeMeters * scale * 2f
                    dst.z = getBathymetryAt(cx, cy + scale) - getBathymetryAt(cx, cy - scale)
                    dst.normalize()
                }
            },
            if (colorMap == null) object : TerrainUtils.ColorMap {
                override fun get(it: Int): Int {
                    val i = coarseIndexToFine(w, h, cw, scale, it)
                    val height = bathymetry[i]
                    return getLandColor(height)
                }
            } else object : TerrainUtils.ColorMap {
                override fun get(it: Int): Int {
                    val i = coarseIndexToFine(w, h, cw, scale, it)
                    val height = bathymetry[i]
                    return colorMap.getColor(height)
                }
            }
        )
    }

    private fun generateFluidMesh(mesh: ProceduralMesh) {
        val bathymetry = bathymetry
        val fluidHeight = fluidHeight
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.read(colorMap, false)
        val fluidMomentumX = fluidMomentumX
        val fluidMomentumY = fluidMomentumY
        val scale = max(1, coarsening)
        val w = width + 2
        val h = height + 2
        val cw = ceilDiv(w, scale)
        val ch = ceilDiv(h, scale)
        val getColor: TerrainUtils.ColorMap = when (visualization) {
            Visualisation.HEIGHT_MAP -> {
                maxVisualizedValue = 0f
                if (colorMap == null) getWaterColor
                else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        val fh = fluidHeight[i]
                        return if (fh > 0f) {// under water, surface color
                            colorMap.getColor(-fh)
                        } else colorMap.getColor(bathymetry[i]) // land color
                    }
                }
            }
            Visualisation.MOMENTUM_X -> {
                val maxMomentum = getMaxMomentum()
                maxVisualizedValue = maxMomentum
                val momentumScale = 1f / max(maxMomentum, 1e-38f)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        return getColor11(fluidMomentumX[i] * momentumScale)
                    }
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        val fh = fluidHeight[i]
                        return if (fh > 0f) {// under water, surface color
                            getColor11(fluidMomentumX[i] * momentumScale)
                        } else colorMap.getColor(bathymetry[i]) // land color
                    }
                }
            }
            Visualisation.MOMENTUM_Y -> {
                val maxMomentum = getMaxMomentum()
                maxVisualizedValue = maxMomentum
                val momentumScale = 1f / max(maxMomentum, 1e-38f)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        return getColor11(fluidMomentumY[i] * momentumScale)
                    }
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        val fh = fluidHeight[i]
                        return if (fh > 0f) {// under water, surface color
                            getColor11(fluidMomentumY[i] * momentumScale)
                        } else colorMap.getColor(bathymetry[i]) // land color
                    }
                }
            }
            Visualisation.MOMENTUM -> {
                val maxMomentum = getMaxMomentum() // not ideal
                maxVisualizedValue = maxMomentum
                val momentumScale = 1f / max(maxMomentum, 1e-38f)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        return getColor11(length(fluidMomentumX[i], fluidMomentumY[i]) * momentumScale)
                    }
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        val fh = fluidHeight[i]
                        return if (fh > 0f) {// under water, surface color
                            getColor11(length(fluidMomentumX[i], fluidMomentumY[i]) * momentumScale)
                        } else colorMap.getColor(bathymetry[i]) // land color
                    }
                }
            }
            Visualisation.WATER_SURFACE -> {
                var maxHeight = 0f
                for (it in fluidHeight.indices) {
                    val fh = fluidHeight[it]
                    if (fh > 0f) {
                        val s = abs(fh + bathymetry[it])
                        if (s > maxHeight) maxHeight = s
                    }
                }
                maxVisualizedValue = maxHeight
                val heightScale = 1f / max(1e-38f, maxHeight)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        return getColor11((fluidHeight[i] + bathymetry[i]) * heightScale)
                    }
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val i = coarseIndexToFine(w, h, cw, scale, it)
                        val fh = fluidHeight[i]
                        val ba = bathymetry[i]
                        return if (fh > 0f) {// under water, surface color
                            getColor11((fh + ba) * heightScale)
                        } else colorMap.getColor(ba) // land color
                    }
                }
            }
        }
        // mesh.materials = fluidMaterialList
        generateRegularQuadHeightMesh(
            cw, ch, 0, cw, cellSizeMeters * w.toFloat() / cw.toFloat(), mesh.mesh2,
            object : TerrainUtils.HeightMap {
                override fun get(it: Int): Float {
                    val i = coarseIndexToFine(w, h, cw, scale, it)
                    return fluidHeight[i] + bathymetry[i]
                }
            },
            object : TerrainUtils.NormalMap {
                override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                    val cx = x * scale
                    val cy = y * scale
                    dst.x = getSurfaceHeightAt(cx + scale, cy) - getSurfaceHeightAt(cx - scale, cy)
                    dst.y = cellSizeMeters * scale * 2f
                    dst.z = getSurfaceHeightAt(cx, cy + scale) - getSurfaceHeightAt(cx, cy - scale)
                    dst.normalize()
                }
            },
            getColor
        )
    }

    private fun getMaxMomentum(): Float {
        val xs = fluidMomentumX
        val ys = fluidMomentumY
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (f in xs) {
            if (f.isFinite()) {
                if (f < min) min = f
                if (f > max) max = f
            }
        }
        for (f in ys) {
            if (f.isFinite()) {
                if (f < min) min = f
                if (f > max) max = f
            }
        }
        return max(-min, max)
    }

    private val getWaterColor = object : TerrainUtils.ColorMap {
        override fun get(it: Int) = getWaterColor(fluidHeight[it])
    }

    private fun getLandColor(height: Float): Int {
        return -1
    }

    private fun getWaterColor(height: Float): Int {
        return mixARGB(0xabbee3, 0x103273, clamp(height * 0.1f, 0f, 1f))
    }

    private fun getColor11(f: Float, pos: Int = 0xff0000, zero: Int = -1, neg: Int = 0x0055ff): Int {
        if (!f.isFinite()) return 0xff00ff
        return if (f < 0f) mixARGB(neg, zero, f + 1f) else mixARGB(zero, pos, f)
    }

    fun step(dt: Float, numMaxIterations: Int = 10): Float {
        var done = 0f
        var i = 0
        val t0 = System.nanoTime()
        while (done < dt && i++ < numMaxIterations) {
            val computeTimeStepInterval = computeTimeStepInterval
            if (computeTimeStepInterval < 2 || maxTimeStep <= 0f || timeStepIndex % computeTimeStepInterval == 0) {
                maxTimeStep = computeMaxTimeStep()
            }
            val step = min(dt - done, maxTimeStep)
            if (step > 0f && step.isFinite()) {
                val scaling = step / cellSizeMeters
                computeStep(scaling)
                done += step
                timeStepIndex++
            } else break
            val t1 = System.nanoTime()
            if (t1 - t0 > 1e9 / 60f) break
            Thread.sleep(0) // for interrupts
        }
        return done
    }

    private fun copy(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
        System.arraycopy(src, 0, dst, 0, src.size)
        return dst
    }

    fun computeStep(scaling: Float) {

        val width = width
        val height = height

        val h0 = fluidHeight
        val hu0 = fluidMomentumX

        setGhostOutflow(width, height, h0)
        setGhostOutflow(width, height, hu0)

        val h1 = copy(h0, tmpH)
        val hu1 = copy(hu0, tmpHuX)

        val b = bathymetry

        val stride = stride
        val gravity = gravity

        // step on x axis
        threadPool.processBalanced(-1, height + 1, 4) { y0, y1 ->
            val tmp4f = tmpV4ByThread.get()
            for (y in y0 until y1) {
                var index0 = getIndex(-1, y)
                for (x in -1 until width) {
                    FWaveSolver.solve(index0, index0 + 1, h0, hu0, b, h1, hu1, gravity, scaling, tmp4f)
                    index0++
                }
            }
        }

        val hv1 = fluidMomentumY

        setGhostOutflow(width, height, h1)
        setGhostOutflow(width, height, hv1)

        // copy data, only height has changed, so only height needs to be switched
        val h2 = copy(h1, h0)
        val hv2 = copy(hv1, tmpHuY)

        // step on y axis
        threadPool.processBalanced(-1, height, 4) { y0, y1 ->
            val tmp4f = tmpV4ByThread.get()
            for (y in y0 until y1) {
                var index0 = getIndex(0, y)
                for (x in 0 until width) {
                    FWaveSolver.solve(index0, index0 + stride, h1, hv1, b, h2, hv2, gravity, scaling, tmp4f)
                    index0++
                }
            }
        }

        // switch the buffers for the momentum
        var tmp = fluidMomentumX
        fluidMomentumX = tmpHuX
        tmpHuX = tmp

        tmp = fluidMomentumY
        fluidMomentumY = tmpHuY
        tmpHuY = tmp

    }

    fun setGhostOutflow(width: Int, height: Int, v: FloatArray) {

        // set the ghost zone to be outflow conditions
        for (y in -1..height) {
            val outside = getIndex(-1, y)
            val inside = getIndex(0, y)
            v[outside] = v[inside]
        }
        for (y in -1..height) {
            val outside = getIndex(width, y)
            val inside = getIndex(width - 1, y)
            v[outside] = v[inside]
        }
        for (x in -1..width) {
            val outside = getIndex(x, -1)
            val inside = getIndex(x, 0)
            v[outside] = v[inside]
        }
        for (x in -1..width) {
            val outside = getIndex(x, height)
            val inside = getIndex(x, height - 1)
            v[outside] = v[inside]
        }
    }

    fun computeMaxTimeStep(): Float {
        val width = width
        val height = height
        val h = fluidHeight
        val hu = fluidMomentumX
        val hv = fluidMomentumY
        val gravity = gravity
        var maxVelocity = 0f
        // check for out of bounds conditions
        h[getIndex(-1, -1)]
        h[getIndex(width, height)]
        threadPool.processBalanced(0, height, true) { y0, y1 ->
            var maxVelocityI = 0f
            for (y in y0 until y1) {
                for (x in 0 until width) {
                    val index = getIndex(x, y)
                    val hi = h[index]
                    if (hi > 0f) {
                        val impulse = max(abs(hu[index]), abs(hv[index]))
                        val velocity = impulse / hi
                        val expectedVelocity = velocity + sqrt(gravity * hi)
                        if (expectedVelocity > maxVelocityI) maxVelocityI = expectedVelocity
                    }
                }
            }
            synchronized(threadPool) {
                maxVelocity = max(maxVelocity, maxVelocityI)
            }
        }
        val is2D = width > 1 || height > 1
        val cflFactor = if (is2D) cfl2d else 0.5f
        return cflFactor * cellSizeMeters / maxVelocity
    }

    @NotSerializedProperty
    var lastChange = 0L

    @NotSerializedProperty
    val currentMousePos = Vector3f()

    @NotSerializedProperty
    val lastMousePos = Vector3f()

    fun getMousePos(dst: Vector3f): Vector3f {
        val hit = Raycast.raycast(
            entity!!,
            RenderView.camPosition,
            RenderView.mouseDir,
            1e6,
            Raycast.TypeMask.TRIANGLES,
            -1,
            true
        )
        if (hit != null) {
            val transform = transform!!
            val global2Local = transform.globalTransform.invert(Matrix4x3d())
            val localPosition = global2Local.transformPosition(hit.positionWS, Vector3d())
            val centerX = width * 0.5f
            val centerY = height * 0.5f
            // the local grid positions
            val cellX = (localPosition.x / cellSizeMeters).toFloat() + centerX
            val cellY = (localPosition.z / cellSizeMeters).toFloat() + centerY
            dst.set(cellX, cellY, hit.distance.toFloat())
        } else dst.z = -1f
        return dst
    }

    /*fun distanceSquaredToLineSegment(vx: Float, vy: Float, wx: Float, wy: Float, px: Float, py: Float): Float {
        // from https://stackoverflow.com/questions/849211/shortest-distance-between-a-point-and-a-line-segment
        // return minimum distance between line segment vw and point p
        val dwx = wx - vx
        val dwy = wy - vy
        val l2 = dwx * dwx + dwy * dwy  // i.e. |w-v|^2 -  avoid a sqrt
        val dpx = px - vx
        val dpy = py - vy
        if (l2 == 0f) return dpx * dpx + dpy * dpy
        // consider the line extending the segment, parameterized as v + t (w - v).
        // we find projection of point p onto the line.
        // it falls where t = [(p-v) . (w-v)] / |w-v|^2
        // we clamp t from [0,l2] to handle points outside the segment vw.
        val t = clamp(dpx * dwx + dpy * dwy, 0f, l2) / l2
        // projection falls on the segment, itself - p
        val qx = dpx - t * dwx
        val qy = dpy - t * dwy
        return qx * qx + qy * qy
    }*/

    override fun onEditMove(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        if (Input.isLeftDown) {
            // critical velocity: sqrt(g*h), so make it that we draw that within 10s
            // todo modes: drag fluid down, drag fluid up, todo start wave here to left/right/top/bottom/...
            // todo add fluid, remove fluid
            val time = System.nanoTime()
            val minDt = 1f / 60f
            val deltaTime = (time - lastChange) * 1e-9f
            when {
                deltaTime in minDt..1f -> {
                    val cmp = currentMousePos
                    val lmp = lastMousePos
                    getMousePos(cmp)
                    if (cmp.z > 0f && lmp.z > 0f && cmp != lmp) {
                        // convert global hit coordinates into local space
                        // convert brush size into local coordinates
                        val brushSize = cmp.z * 0.1f / cellSizeMeters
                        // compute the area of effect
                        val cellMinX = max((min(cmp.x, lmp.x) - brushSize).toInt(), 0)
                        val cellMaxX = min((max(cmp.x, lmp.x) + brushSize).toInt(), width - 1)
                        val cellMinY = max((min(cmp.y, lmp.y) - brushSize).toInt(), 0)
                        val cellMaxY = min((max(cmp.y, lmp.y) + brushSize).toInt(), height - 1)
                        // compute brush strength
                        val brushStrength = (if (Input.isShiftDown) -1f else +1f) * 1e4f * deltaTime
                        // apply brush with circular falloff
                        val invBrushSize = 1f / brushSize
                        val fluidHeight = fluidHeight
                        val fluidMomentumX = fluidMomentumX
                        val fluidMomentumY = fluidMomentumY
                        val gravity = gravity
                        val minPerThread = 4000 / (cellMaxX - cellMinX)
                        processBalanced(cellMinY, cellMaxY + 1, minPerThread) { y0, y1 ->
                            val vx = cmp.x
                            val vy = cmp.y
                            val wx = lmp.x
                            val wy = lmp.y
                            val dwx = wx - vx
                            val dwy = wy - vy
                            val lineLengthSquared = dwx * dwx + dwy * dwy
                            val dwx2 = dwx / lineLengthSquared
                            val dwy2 = dwy / lineLengthSquared
                            for (yi in y0 until y1) {
                                for (xi in cellMinX..cellMaxX) {
                                    val px = xi.toFloat()
                                    val py = yi.toFloat()
                                    // minimum distance between line segment vw and point p, reformed a lot
                                    // from https://stackoverflow.com/questions/849211/shortest-distance-between-a-point-and-a-line-segment
                                    var dpx = px - vx
                                    var dpy = py - vy
                                    // "percentage" from cmp to lmp
                                    // val t = clamp(dpx * dwx + dpy * dwy, 0f, lineLengthSquared) * lineLengthSqInv
                                    val t = dpx * dwx2 + dpy * dwy2
                                    if (t in 0f..1f) {// on the left/right of the line segment
                                        // if we wouldn't have this if(), we'd draw caps at the end of the segment
                                        dpx -= t * dwx
                                        dpy -= t * dwy
                                        val d2 = dpx * dpx + dpy * dpy
                                        val index = getIndex(xi, yi)
                                        val strength = brushStrength * getNormalizedBrushShape(d2 * invBrushSize) *
                                                sqrt(fluidHeight[index] * gravity) * invBrushSize
                                        fluidMomentumX[index] += strength * dwx
                                        fluidMomentumY[index] += strength * dwy
                                    }
                                }
                            }
                        }
                        // something has changed, to update the mesh
                        invalidateMesh()
                    }
                    // needs to be now (instead of when we started to process this update), so the system doesn't hang, when processing uses too much time
                    lastChange = System.nanoTime()
                    lastMousePos.set(currentMousePos)
                }
                deltaTime > minDt -> {
                    // deltaTime was long enough,
                    // but it's soo long ago,
                    // that we should reset the cursor
                    lastChange = time
                    getMousePos(lastMousePos)
                }
            }
            return true
        } else lastMousePos.z = -1f // invalidate mouse position
        return false
    }

    private fun slerp(x: Float): Float {
        return x * x * (3 - 2 * x)
    }

    private fun getNormalizedBrushShape(d2: Float): Float {
        return if (d2 < 1f) slerp(1f - d2) else 0f
    }

    override fun clone() = FluidSim(this)

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as FluidSim
        // copy the scalar properties
        clone.width = width
        clone.height = height
        clone.cellSizeMeters = cellSizeMeters
        clone.gravity = gravity
        clone.cfl2d = cfl2d
        clone.timeFactor = timeFactor
        // copy the array properties
        clone.fluidHeight = copy(fluidHeight)
        clone.fluidMomentumX = copy(fluidMomentumX)
        clone.fluidMomentumY = copy(fluidMomentumY)
        clone.bathymetry = copy(bathymetry)
        val size = fluidHeight.size
        clone.tmpH = FloatArray(size)
        clone.tmpHuX = FloatArray(size)
        clone.tmpHuY = FloatArray(size)
        clone.needsUpdate = needsUpdate
        clone.wantsReset = wantsReset
        clone.colorMap = colorMap
        clone.computeTimeStepInterval = computeTimeStepInterval
        // copy the references
        clone.setup = getInClone(setup, clone)
        clone.bathymetryMesh = getInClone(bathymetryMesh, clone)
    }

    override fun onDestroy() {
        super.onDestroy()
        mesh2.destroy()
        computingThread?.interrupt()
    }

    override val className: String = "Tsunamis/FluidSim"

    companion object {
        val threadPool = ProcessingGroup("TsunamiSim", 1f)
        val tmpV4ByThread = ThreadLocal2 { FloatArray(4) }
        val fluidMaterialList by lazy {
            val prefab = Prefab("Material")
            prefab.createInstance() // ensure there is an instance
            prefab.setProperty("shader", YTextureShader)
            listOf(InnerTmpFile.InnerTmpPrefabFile(prefab))
        }
        private val defaultColorMap = getReference("res://colormaps/globe.xml")
        private val LOGGER = LogManager.getLogger(FluidSim::class)
        private val f0 = FloatArray(0)
    }

}