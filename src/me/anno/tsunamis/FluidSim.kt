package me.anno.tsunamis

import me.anno.ecs.annotations.*
import me.anno.ecs.components.cache.MaterialCache
import me.anno.ecs.components.mesh.ManualProceduralMesh
import me.anno.ecs.components.mesh.Material
import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.ecs.components.mesh.TypeValue
import me.anno.ecs.interfaces.CustomEditMode
import me.anno.ecs.prefab.Prefab
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.engine.raycast.Raycast
import me.anno.engine.ui.render.RenderView
import me.anno.gpu.GFX
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.Texture2D
import me.anno.input.Input
import me.anno.io.files.FileReference
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.serialization.NotSerializedProperty
import me.anno.io.serialization.SerializedProperty
import me.anno.io.zip.InnerTmpFile
import me.anno.maths.Maths.ceilDiv
import me.anno.maths.Maths.clamp
import me.anno.tsunamis.draw.Drawing
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.EngineType
import me.anno.tsunamis.engine.TsunamiEngine
import me.anno.tsunamis.engine.gpu.ComputeEngine
import me.anno.tsunamis.engine.gpu.GraphicsEngine
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.io.NetCDFExport
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.ui.base.groups.PanelListY
import me.anno.ui.base.text.TextPanel
import me.anno.ui.editor.PropertyInspector
import me.anno.ui.editor.SettingCategory
import me.anno.ui.style.Style
import me.anno.utils.ShutdownException
import me.anno.utils.hpc.ProcessingGroup
import me.anno.utils.hpc.ThreadLocal2
import me.anno.utils.types.Lists.firstInstanceOrNull
import org.apache.logging.log4j.LogManager
import org.joml.*
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

// todo at the end of the tsunami scenario, there seems to be a wall even without wall enabled...

/**
 * simple 3d mesh, which simulates water
 * */
@ExecuteInEditMode
class FluidSim : ProceduralMesh, CustomEditMode {

    constructor()

    constructor(base: FluidSim) {
        base.copy(this)
    }

    var engineType: EngineType = EngineType.CPU
        set(value) {
            if (field != value) {
                field = value
                invalidateSetup()
            }
        }

    @NotSerializedProperty
    var engine: TsunamiEngine? = null

    @NotSerializedProperty
    var maxVisualizedValueInternally = 0f

    @Group("Visuals")
    @SerializedProperty
    var maxVisualizedValue = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
            }
        }

    @Group("Visuals")
    @Type("ManualProceduralMesh/PrefabSaveable")
    @SerializedProperty
    var bathymetryMesh: ManualProceduralMesh? = null

    @NotSerializedProperty
    var computeOnly = false

    @Group("Visuals")
    @SerializedProperty
    var colorMap: FileReference = defaultColorMap
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
                invalidateBathymetryMesh()
            }
        }

    @Group("Visuals")
    @SerializedProperty
    var colorMapScale = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
                invalidateBathymetryMesh()
            }
        }

    @NotSerializedProperty
    val colorMapTexture = Texture2D("ColorMap", 128, 1, 1)

    @Group("Visuals")
    @SerializedProperty
    var visualization = Visualisation.HEIGHT_MAP
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
            }
        }

    @Group("Size")
    @Order(0)
    @SerializedProperty
    var width = 10
        set(value) {
            if (field != value) {
                field = value
                invalidateSetup()
            }
        }

    @Group("Size")
    @Order(1)
    @SerializedProperty
    var height = 10
        set(value) {
            if (field != value) {
                field = value
                invalidateSetup()
            }
        }

    @Group("Size")
    @Order(2)
    @Range(1.0, 1000.0)
    @SerializedProperty
    var coarsening = 1
        set(value) {
            if (field != value && value >= 1) {
                field = value
                invalidateFluid()
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
                invalidateFluid()
                invalidateBathymetryMesh()
            }
        }

    @DebugProperty
    @NotSerializedProperty
    var maxTimeStep = 0f

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
        set(value) {
            if (field != value) {
                field = value
                computingThread?.interrupt()
                computingThread = null
            }
        }

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

    @NotSerializedProperty
    var wantsReset = false

    @DebugAction
    @Suppress("UNUSED")
    fun resetSimulation() {
        wantsReset = true
        invalidateFluid()
    }

    @DebugAction
    @Suppress("UNUSED")
    fun setFluidZero() {
        val engine = engine ?: return
        computingThread?.interrupt()
        computingThread = null
        synchronized(workMutex) {
            engine.setZero()
        }
        invalidateFluid()
    }

    @DebugAction
    @DebugTitle("Export as NetCDF")
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

    @NotSerializedProperty
    val workMutex = Any()

    @NotSerializedProperty
    @DebugProperty
    var maxMomentumX = 0f

    @NotSerializedProperty
    @DebugProperty
    var maxMomentumY = 0f

    @NotSerializedProperty
    @DebugProperty
    var maxSurfaceHeight = 0f

    @NotSerializedProperty
    var computingThread: Thread? = null

    @SerializedProperty
    var fluidHeightScale = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
            }
        }

    fun getIndex(x: Int, y: Int): Int {
        val width = width
        val height = height
        val lx = clamp(x + 1, 0, width + 1)
        val ly = clamp(y + 1, 0, height + 1)
        return lx + ly * (width + 2)
    }

    @Suppress("UNUSED")
    fun getSurfaceHeightAt(x: Int, y: Int): Float {
        return engine!!.getSurfaceHeightAt(x, y)
    }

    @Suppress("UNUSED")
    fun getFluidHeightAt(x: Int, y: Int): Float {
        return engine!!.getFluidHeightAt(x, y)
    }

    @Suppress("UNUSED")
    fun getMomentumXAt(x: Int, y: Int): Float {
        return engine!!.getMomentumXAt(x, y)
    }

    @Suppress("UNUSED")
    fun getMomentumYAt(x: Int, y: Int): Float {
        return engine!!.getMomentumYAt(x, y)
    }

    @Suppress("UNUSED")
    fun getBathymetryAt(x: Int, y: Int): Float {
        return engine!!.getBathymetryAt(x, y)
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
    fun setDimensionsBySetup() {
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
        var engine = engine
        return if (engine == null || engine.width != width || engine.height != height || wantsReset) {

            val setup = setup ?: return false
            if (!setup.isReady()) return false

            // reset engine
            // to make it cheaper, we could replace them...
            engine?.destroy()
            engine = when (engineType) {
                EngineType.CPU -> CPUEngine(w, h)
                EngineType.GPU_GRAPHICS -> GraphicsEngine(w, h)
                EngineType.GPU_COMPUTE -> ComputeEngine(w, h)
            }
            this.engine = engine

            wantsReset = false
            hasValidBathymetryMesh = false

            try {
                engine.init(this, setup, gravity)
            } catch (e: Exception) {
                // fallback
                e.printStackTrace()
                engine = CPUEngine(w, h)
                this.engine = engine
                engine.init(this, setup, gravity)
            }

            timeStepIndex = 0
            maxTimeStep = computeMaxTimeStep()

            true
        } else true
    }

    fun initWithSetup(setup: FluidSimSetup) {
        this.setup = setup
        ensureFieldSize()
    }

    override fun onUpdate(): Int {
        if (ensureFieldSize()) {
            val bm = bathymetryMesh
            if (!hasValidBathymetryMesh && bm != null) {
                generateBathymetryMesh(bm)
            }
            if (!isPaused) {
                val engine = engine!!
                val dt = GFX.deltaTime * timeFactor
                if (dt > 0f) {
                    if (engine.supportsAsyncCompute()) {
                        if (computingThread == null) {
                            computingThread = thread {
                                synchronized(workMutex) {
                                    try {
                                        step(dt)
                                    } catch (e: InterruptedException) {
                                        // don't care too much
                                    } catch (e: ShutdownException) {
                                        // don't care
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        // must be called under all circumstances
                                        computingThread = null
                                    }
                                }
                                invalidateFluid()
                            }
                        }
                    } else {
                        synchronized(workMutex) {
                            step(dt)
                        }
                        invalidateFluid()
                    }
                }
            }
        }
        return 1 // update every tick
    }

    override fun generateMesh() {
        if (ensureFieldSize()) {
            mesh2.hasHighPrecisionNormals = true
            val engine = engine
            if (engine != null && engine.width == width && engine.height == height) {
                generateFluidMesh(this)
            } // else wait for simulation
        }
    }

    @SerializedProperty
    var flipBathymetryNormal = false
        set(value) {
            if (field != value) {
                field = value
                invalidateBathymetryMesh()
                invalidateFluid()
            }
        }

    @SerializedProperty
    var useTextureData: Boolean = true

    @NotSerializedProperty
    val useTextureData2: Boolean
        get() = engine?.run { (useTextureData && supportsTexture()) || !supportsMesh() } ?: useTextureData

    private fun findVisualScale() {
        maxVisualizedValueInternally = if (maxVisualizedValue == 0f) {
            when (visualization) {
                Visualisation.HEIGHT_MAP -> 0f
                Visualisation.MOMENTUM_X -> maxMomentumX
                Visualisation.MOMENTUM_Y -> maxMomentumY
                Visualisation.MOMENTUM -> max(maxMomentumX, maxMomentumY) // not ideal
                Visualisation.WATER_SURFACE -> maxSurfaceHeight
            }
        } else maxVisualizedValue
    }

    private fun setMaterialProperties(
        material: Material,
        colorMap: ColorMap?,
        cellSize: Float,
        cw: Int,
        ch: Int,
        fluidTexture: Texture2D
    ) {
        material["fluidData"] = TypeValue(GLSLType.S2D, fluidTexture)
        if (colorMap != null) {
            colorMap.createTexture(colorMapTexture, false)
            colorMapTexture.clamping = Clamping.CLAMP
            material["colorMap"] = TypeValue(GLSLType.S2D, colorMapTexture)
            val newMax = colorMap.max * colorMapScale
            val newMin = colorMap.min * colorMapScale
            val delta = (newMax - newMin)
            val scale2 = Vector2f(1f / delta, -newMin / delta)
            material["colorMapScale"] = TypeValue(GLSLType.V2F, scale2)
        }
        if (engineType == EngineType.CPU) {
            material["cellSize"] = TypeValue(GLSLType.V1F, cellSize)
        } else {
            material["cellSize"] = TypeValue(GLSLType.V1F, cellSizeMeters)
        }
        // intended to solve z-fighting; doesn't work that well
        // when culling is enabled, this won't be a problem anyways
        val dz = if (flipBathymetryNormal) cellSize / 100f else 0f
        val cellOffset = Vector3f(
            -(cw - 2) * 0.5f * cellSize, dz,
            -(ch - 2) * 0.5f * cellSize
        )
        material["cellOffset"] = TypeValue(GLSLType.V3F, cellOffset)
        material["visualization"] = TypeValue(GLSLType.V1I, visualization.id)
        val visScale = 1f / max(1e-38f, maxVisualizedValueInternally)
        material["visScale"] = TypeValue(GLSLType.V1F, visScale)
        material["visualMask"] = TypeValue(
            GLSLType.V4F,
            when (visualization) {
                Visualisation.MOMENTUM_X -> Vector4f(0f, visScale, 0f, 0f)
                Visualisation.MOMENTUM_Y -> Vector4f(0f, 0f, visScale, 0f)
                else -> Vector4f(visScale, 0f, 0f, visScale)
            }
        )
        material["heightMask"] = TypeValue(GLSLType.V4F, Vector4f(1f, 0f, 0f, 1f))
        material["fluidHeightScale"] = TypeValue(GLSLType.V1F, fluidHeightScale)
        material["coarseSize"] = TypeValue(GLSLType.V2I, Vector2i(cw - 2, ch - 2))
    }

    private fun ensureTriangleCount(mesh: ProceduralMesh, cw: Int, ch: Int) {
        val mesh2 = mesh.mesh2
        val targetSize = (cw - 3) * (ch - 3) * 2
        if (mesh2.proceduralLength != targetSize) {
            mesh2.proceduralLength = targetSize
            mesh2.positions = f12
            mesh2.normals = f0
            mesh2.color0 = i0
            invalidateMesh()
        }
        // todo it would be cool if we had a rough mesh on the cpu, and a fine one on the gpu
        // todo we should set the bounding box positions, so it is clickable
    }

    private fun generateFluidMesh(mesh: ProceduralMesh) {
        val engine = engine ?: return
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.read(colorMap, false)
        val scale = max(1, coarsening)
        val w = width + 2
        val h = height + 2
        val cw = ceilDiv(w, scale)
        val ch = ceilDiv(h, scale)
        findVisualScale()
        val cellSize = getCellSize(cellSizeMeters, w, cw)
        if (useTextureData2) {
            mesh.materials = fluidMaterialList
            val material = MaterialCache[fluidMaterialList[0]]
            if (material != null) {
                val fluidTexture = engine.createFluidTexture(w, h, cw, ch)
                setMaterialProperties(material, colorMap, cellSize, cw, ch, fluidTexture)
            }
            ensureTriangleCount(mesh, cw, ch)
        } else {
            mesh.materials = emptyList()
            mesh.mesh2.proceduralLength = 0
            engine.createFluidMesh(
                w, h, cw, ch, cellSize, scale, fluidHeightScale,
                visualization, colorMap, colorMapScale, maxVisualizedValueInternally, mesh
            )
        }
    }

    private fun getCellSize(cellSizeMeters: Float, w: Int, cw: Int): Float {
        return cellSizeMeters * (w - 1f) / (cw - 1f)
    }

    private fun generateBathymetryMesh(mesh: ProceduralMesh) {
        hasValidBathymetryMesh = true
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.read(colorMap, false)
        val scale = max(1, coarsening)
        val w = width + 2
        val h = height + 2
        val cw = ceilDiv(w, scale)
        val ch = ceilDiv(h, scale)
        val cellSize = getCellSize(cellSizeMeters, w, cw)
        engine!!.createBathymetryMesh(
            w, h, cw, ch, scale, cellSize,
            colorMap, colorMapScale, flipBathymetryNormal, mesh
        )
    }

    fun step(dt: Float, numMaxIterations: Int = 10): Float {
        var done = 0f
        var i = 0
        val t0 = System.nanoTime()
        val engine = engine!!
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
        engine.updateStatistics(this)
        return done
    }

    @SerializedProperty
    var synchronize = false

    fun computeStep(scaling: Float) {
        val engine = engine ?: return
        engine.step(gravity, scaling)
        if (synchronize) engine.synchronize()
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
        val maxVelocity = engine!!.computeMaxVelocity(gravity)
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

    @Group("Drawing")
    @SerializedProperty
    var brushSize = 30f * 250f

    @Group("Drawing")
    @SerializedProperty
    var brushStrength = 5f

    fun invalidateSetup() {
        if (computeOnly) return
        computingThread?.interrupt()
        computingThread = null
        engine?.destroy()
        engine = null
        invalidateFluid()
        invalidateBathymetryMesh()
    }

    fun invalidateFluid() {
        if (computeOnly) return
        if (GFX.isGFXThread()) {
            if (useTextureData2) {
                generateFluidMesh(this)
            }
            invalidateMesh()
            ensureBuffer()
        } else {
            GFX.addGPUTask(1) {
                invalidateFluid()
            }
        }
    }

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
                        synchronized(workMutex) {
                            Drawing.drawLineSegment(cmp, lmp, this)
                        }
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

    /* only can be used for in-game stuff, so drawing would be a little complicated */
    /*override fun onDrawGUI() {
        super.onDrawGUI()
        val instance = RenderView.currentInstance
        renderPurely {
            DrawRectangles.drawRect(
                instance.x, instance.y,
                instance.w / 2, instance.h / 2,
                -1
            )
        }
    }*/

    override fun createInspector(
        list: PanelListY,
        style: Style,
        getGroup: (title: String, description: String, dictSubPath: String) -> SettingCategory
    ) {
        super.createInspector(list, style, getGroup)
        val title = list.findFirstInAll { it is TextPanel && it.text == "Color Map Scale" } as? TextPanel
        if (title != null) {
            val group = title.listOfHierarchyReversed.firstInstanceOrNull<PanelListY>()
            if (group != null) {
                var indexInList = 0
                title.listOfPanelHierarchy {
                    if (it.uiParent === group) {
                        indexInList = it.indexInParent
                    }
                }
                group.add(indexInList, TextPanel("Color Map:", style))
                group.add(indexInList + 1, ColorMapPreview(this, style))
            } else LOGGER.warn("Group not found!")
        } else LOGGER.warn("Title was not found!")
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
        /*// copy the array properties
        clone.fluidHeight = copy(fluidHeight)
        clone.fluidMomentumX = copy(fluidMomentumX)
        clone.fluidMomentumY = copy(fluidMomentumY)
        clone.bathymetry = copy(bathymetry)
        val size = fluidHeight.size
        clone.tmpH = FloatArray(size)
        clone.tmpHuX = FloatArray(size)
        clone.tmpHuY = FloatArray(size)*/
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
        computingThread = null
        engine?.destroy()
        engine = null
    }

    override val className: String = "Tsunamis/FluidSim"

    @NotSerializedProperty
    val fluidMaterialList by lazy { createMaterials() }
    // val solidMaterialList by lazy { createMaterials() }

    companion object {

        // todo check coast-condition for errors

        /**
         * scales down an index from a coarse grid to a fine grid, preserves the border
         * */
        fun coarseIndexToFine(x: Int, w: Int, cw: Int): Int {
            return when {
                x < 2 -> x
                cw - x <= 2 -> x + (w - cw)
                cw <= 4 -> x
                else -> 2 + (x - 2) * (w - 4) / (cw - 4)
            }
        }

        fun coarseIndexToFine(w: Int, h: Int, cw: Int, ch: Int, i: Int): Int {
            return if (cw == w) {
                i
            } else {
                val x = i % cw
                val y = i / cw
                val nx = coarseIndexToFine(x, w, cw)
                val ny = coarseIndexToFine(y, h, ch)
                nx + ny * w
            }
        }

        private fun createMaterials(): List<FileReference> {
            val prefab = Prefab("Material")
            prefab.createInstance() // ensure there is an instance
            prefab.setProperty("shader", YTextureShader)
            return listOf(InnerTmpFile.InnerTmpPrefabFile(prefab))
        }

        val threadPool = ProcessingGroup("TsunamiSim", 1f)
        val tmpV4ByThread = ThreadLocal2 { FloatArray(4) }
        private val defaultColorMap = getReference("res://colormaps/globe.xml")
        private val LOGGER = LogManager.getLogger(FluidSim::class)
        val f0 = FloatArray(0)
        val i0 = IntArray(0)
        val f12 = FloatArray(12)
    }

}