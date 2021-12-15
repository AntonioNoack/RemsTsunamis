package me.anno.tsunamis

import me.anno.ecs.annotations.*
import me.anno.ecs.components.mesh.ManualProceduralMesh
import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.ecs.components.mesh.terrain.TerrainUtils
import me.anno.ecs.components.mesh.terrain.TerrainUtils.generateRegularQuadHeightMesh
import me.anno.ecs.interfaces.CustomEditMode
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.engine.raycast.Raycast
import me.anno.engine.ui.render.RenderView
import me.anno.gpu.GFX
import me.anno.input.Input
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.serialization.NotSerializedProperty
import me.anno.io.serialization.SerializedProperty
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.utils.hpc.ProcessingGroup
import me.anno.utils.hpc.ThreadLocal2
import me.anno.utils.maths.Maths.clamp
import me.anno.utils.maths.Maths.mixARGB
import org.apache.logging.log4j.LogManager
import org.joml.Matrix4x3d
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.*

/**
 * simple 3d mesh, which simulates water
 * todo where is that wall of momentum coming from?
 * todo mirror y axis
 * */
@ExecuteInEditMode
class FluidSim : ProceduralMesh, CustomEditMode {

    constructor()

    constructor(base: FluidSim) {
        base.copy(this)
    }

    // todo option to create border mesh for nicer looks

    @Type("ManualProceduralMesh/PrefabSaveable")
    var bathymetryMesh: ManualProceduralMesh? = null

    @SerializedProperty
    var colorMap: FileReference = defaultColorMap

    @NotSerializedProperty
    var wantsReset = false

    @DebugAction
    fun reset() {
        wantsReset = true
        hasValidBathymetryMesh = false
    }

    @DebugAction
    fun invalidateBathymetryMesh() {
        hasValidBathymetryMesh = false
    }

    @NotSerializedProperty
    var hasValidBathymetryMesh = false

    @DebugProperty
    var maxTimeStep = 0f

    @DebugProperty
    var maxVisualizedValue = 0f

    // 0 = height map, 1 = momentum x, 2 = momentum y, 3 = water surface
    @Range(0.0, 3.0)
    @SerializedProperty
    var visualization = 0

    @SerializedProperty
    var width = 10

    @SerializedProperty
    var height = 10

    @NotSerializedProperty
    var fluidHeight: FloatArray = f0

    @NotSerializedProperty
    var fluidMomentumX: FloatArray = f0

    @NotSerializedProperty
    var fluidMomentumY: FloatArray = f0

    @NotSerializedProperty
    var bathymetry: FloatArray = f0

    @SerializedProperty
    var gravity = 9.81f

    @SerializedProperty
    var timeFactor = 1f

    @SerializedProperty
    var cellSizeMeters = 1f
        set(value) {
            if (field != value && value > 0f) {
                field = value
                invalidateMesh()
            }
        }

    @SerializedProperty
    @Range(0.0, 0.5)
    var cfl2d = 0.45f

    @SerializedProperty
    var computeTimeStepInterval = 0

    @NotSerializedProperty
    var timeStepIndex = 0

    val stride get() = width + 2

    @NotSerializedProperty
    private var tmpH = f0

    @NotSerializedProperty
    private var tmpHuX = f0

    @NotSerializedProperty
    private var tmpHuY = f0

    /*@DebugProperty
    val bathInfo: String
        get() = "${bathymetry.minOrNull()} - ${bathymetry.maxOrNull()}, avg: ${bathymetry.average()}\n"

    @DebugProperty
    val heightInfo: String
        get() = "${fluidHeight.minOrNull()} - ${fluidHeight.maxOrNull()}, avg: ${fluidHeight.average()}\n"

    @DebugProperty
    val impulseXInfo: String
        get() = "${fluidMomentumX.minOrNull()} - ${fluidMomentumX.maxOrNull()}, avg: ${fluidMomentumX.average()}\n"

    @DebugProperty
    val impulseYInfo: String
        get() = "${fluidMomentumY.minOrNull()} - ${fluidMomentumY.maxOrNull()}, avg: ${fluidMomentumY.average()}"*/

    fun getIndex(x: Int, y: Int): Int {
        val width = width
        val height = height
        val lx = clamp(x + 1, 0, width + 1)
        val ly = clamp(y + 1, 0, height + 1)
        return lx + ly * (width + 2)
    }

    fun getSurfaceHeightAt(x: Int, y: Int): Float {
        val index = getIndex(x, y)
        return fluidHeight[index] + bathymetry[index]
    }

    fun getFluidHeightAt(x: Int, y: Int): Float {
        return fluidHeight[getIndex(x, y)]
    }

    fun getMomentumXAt(x: Int, y: Int): Float {
        return fluidMomentumX[getIndex(x, y)]
    }

    fun getMomentumYAt(x: Int, y: Int): Float {
        return fluidMomentumY[getIndex(x, y)]
    }

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
        if (ensureFieldSize()) {
            val w = width
            val h = height
            setup.fillHeight(w, h, fluidHeight)
            setup.fillBathymetry(w, h, bathymetry)
            setup.fillMomentumX(w, h, fluidMomentumX)
            setup.fillMomentumY(w, h, fluidMomentumY)
            setGhostOutflow(bathymetry)
        }
    }

    override fun onUpdate(): Int {
        if (ensureFieldSize()) {
            val dt = GFX.deltaTime * timeFactor
            if (dt > 0f) {
                try {
                    step(dt)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            invalidateMesh()
            // why is this function not called automatically?
            if (GFX.isGFXThread()) ensureBuffer()
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

    private fun generateBathymetryMesh(mesh: ProceduralMesh) {
        hasValidBathymetryMesh = true
        val bathymetry = bathymetry
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.getMap(colorMap, false)
        generateRegularQuadHeightMesh(
            width + 2, height + 2, getIndex(-1, -1),
            stride, cellSizeMeters, mesh.mesh2,
            object : TerrainUtils.HeightMap {
                override fun get(it: Int) = bathymetry[it]
            },
            object : TerrainUtils.NormalMap {
                override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                    dst.x = getBathymetryAt(x + 1, y) - getBathymetryAt(x - 1, y)
                    dst.y = cellSizeMeters * 2f
                    dst.z = getBathymetryAt(x, y + 1) - getBathymetryAt(x, y - 1)
                    dst.normalize()
                }
            },
            object : TerrainUtils.ColorMap {
                override fun get(it: Int): Int {
                    val height = bathymetry[it]
                    return colorMap?.getColor(height) ?: getLandColor(height)
                }
            }
        )
    }

    private fun generateFluidMesh(mesh: ProceduralMesh) {
        val bathymetry = bathymetry
        val fluidHeight = fluidHeight
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.getMap(colorMap, false)
        val fluidMomentumX = fluidMomentumX
        val fluidMomentumY = fluidMomentumY
        val getColor: TerrainUtils.ColorMap = when (visualization) {
            1 -> {
                val maxMomentum = getMaxMomentum()
                maxVisualizedValue = maxMomentum
                val momentumScale = 1f / max(maxMomentum, 1e-38f)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int = getColor11(fluidMomentumX[it] * momentumScale)
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val h = fluidHeight[it]
                        return if (h > 0f) {// under water, surface color
                            getColor11(fluidMomentumX[it] * momentumScale)
                        } else colorMap.getColor(bathymetry[it]) // land color
                    }
                }
            }
            2 -> {
                val maxMomentum = getMaxMomentum()
                maxVisualizedValue = maxMomentum
                val momentumScale = 1f / max(maxMomentum, 1e-38f)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int = getColor11(fluidMomentumY[it] * momentumScale)
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val h = fluidHeight[it]
                        return if (h > 0f) {// under water, surface color
                            getColor11(fluidMomentumY[it] * momentumScale)
                        } else colorMap.getColor(bathymetry[it]) // land color
                    }
                }
            }
            3 -> {
                var maxHeight = 0f
                for (it in fluidHeight.indices) {
                    val h = fluidHeight[it]
                    if (h > 0f) {
                        val s = abs(h + bathymetry[it])
                        if (s > maxHeight) maxHeight = s
                    }
                }
                maxVisualizedValue = maxHeight
                val heightScale = 1f / max(1e-38f, maxHeight)
                if (colorMap == null) object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int = getColor11((fluidHeight[it] + bathymetry[it]) * heightScale)
                } else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val h = fluidHeight[it]
                        val b = bathymetry[it]
                        return if (h > 0f) {// under water, surface color
                            getColor11((h + b) * heightScale)
                        } else colorMap.getColor(b) // land color
                    }
                }
            }
            else -> {
                maxVisualizedValue = 0f
                if (colorMap == null) getWaterColor
                else object : TerrainUtils.ColorMap {
                    override fun get(it: Int): Int {
                        val h = fluidHeight[it]
                        return if (h > 0f) {// under water, surface color
                            colorMap.getColor(-h)
                        } else colorMap.getColor(bathymetry[it]) // land color
                    }
                }
            }
        }
        generateRegularQuadHeightMesh(
            width + 2, height + 2, getIndex(-1, -1),
            stride, cellSizeMeters, mesh.mesh2,
            object : TerrainUtils.HeightMap {
                override fun get(it: Int) = fluidHeight[it] + bathymetry[it]
            },
            object : TerrainUtils.NormalMap {
                override fun get(x: Int, y: Int, i: Int, dst: Vector3f) {
                    dst.x = getSurfaceHeightAt(x + 1, y) - getSurfaceHeightAt(x - 1, y)
                    dst.y = cellSizeMeters * 2f
                    dst.z = getSurfaceHeightAt(x, y + 1) - getSurfaceHeightAt(x, y - 1)
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

        setGhostOutflow(h0)
        setGhostOutflow(hu0)

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

        setGhostOutflow(h1)
        setGhostOutflow(hv1)

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

    fun setGhostOutflow(v: FloatArray) {

        // set the ghost zone to be outflow conditions
        val width = width
        val height = height

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
        val stride = stride
        val gravity = gravity
        var maxVelocity = 0f
        threadPool.processBalanced(0, height, true) { y0, y1 ->
            var maxVelocityI = 0f
            for (y in y0 until y1) {
                for (x in 0 until width) {
                    val index = getIndex(x, y)
                    val h0 = h[index]
                    if (h0 > 0f) {
                        val h1 = max(
                            h0, max(
                                max(h[index - 1], h[index + 1]),
                                max(h[index - stride], h[index + stride])
                            )
                        )
                        val impulse = max(abs(hu[index]), abs(hv[index]))
                        val velocity = impulse / h0
                        val expectedVelocity = velocity + sqrt(gravity * h1)
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

    override fun onEditMove(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        if (Input.isLeftDown) {
            // critical velocity: sqrt(g*h), so make it that we draw that within 10s
            // todo modes: drag fluid down, drag fluid up, todo start wave here to left/right/top/bottom/...
            // todo add fluid, remove fluid
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
                // convert global hit coordinates into local space
                val transform = transform!!
                val global2Local = transform.globalTransform.invert(Matrix4x3d())
                val localPosition = global2Local.transformPosition(hit.positionWS, Vector3d())
                val centerX = width * 0.5f
                val centerY = height * 0.5f
                // the local grid positions
                val cellX = (localPosition.x / cellSizeMeters).toFloat() + centerX
                val cellY = (localPosition.z / cellSizeMeters).toFloat() + centerY
                // convert brush size into local coordinates
                val brushSize = (hit.distance * 0.1f / cellSizeMeters).toFloat()
                // compute the area of effect
                val cellMinX = max((cellX - brushSize).toInt(), 0)
                val cellMaxX = min((cellX + brushSize).toInt(), width - 1)
                val cellMinY = max((cellY - brushSize).toInt(), 0)
                val cellMaxY = min((cellY + brushSize).toInt(), height - 1)
                // compute brush strength
                val brushStrength = (if (Input.isShiftDown) -1f else +1f) * 100f * GFX.deltaTime
                // apply brush with circular falloff
                val invBrushSize = 1f / brushSize
                val fluidHeight = fluidHeight
                val fluidMomentumX = fluidMomentumX
                val fluidMomentumY = fluidMomentumY
                val gravity = gravity
                for (yi in cellMinY..cellMaxY) {
                    for (xi in cellMinX..cellMaxX) {
                        val index = getIndex(xi, yi)
                        val dxi = (xi - cellX) * invBrushSize
                        val dyi = (yi - cellY) * invBrushSize
                        val strength =
                            brushStrength * getNormalizedBrushShape(dxi, dyi) * sqrt(fluidHeight[index] * gravity)
                        fluidMomentumX[index] += dxi * strength
                        fluidMomentumY[index] += dyi * strength
                    }
                }
                // something has changed, to update the mesh
                invalidateMesh()
            }
            return true
        }
        return false
    }

    fun getNormalizedBrushShape(x: Float, y: Float): Float {
        val d = sqrt(x * x + y * y)
        return if (d < 1f) cos(d * 3.1416f) * .5f + .5f else 0f
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
    }

    override val className: String = "Tsunamis/FluidSim"

    companion object {
        val threadPool = ProcessingGroup("TsunamiSim", 1f)
        val tmpV4ByThread = ThreadLocal2 { FloatArray(4) }
        private val defaultColorMap = getReference("res://colormaps/globe.xml")
        private val LOGGER = LogManager.getLogger(FluidSim::class)
        private val f0 = FloatArray(0)
    }

}