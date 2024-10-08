package me.anno.tsunamis

import me.anno.Time
import me.anno.ecs.annotations.*
import me.anno.ecs.components.mesh.Mesh
import me.anno.ecs.components.mesh.ProceduralMesh
import me.anno.ecs.components.mesh.material.Material
import me.anno.ecs.components.mesh.material.MaterialCache
import me.anno.ecs.components.mesh.material.utils.TypeValue
import me.anno.ecs.interfaces.CustomEditMode
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.ecs.systems.OnUpdate
import me.anno.engine.raycast.RayQuery
import me.anno.engine.raycast.Raycast
import me.anno.engine.raycast.RaycastMesh
import me.anno.engine.serialization.NotSerializedProperty
import me.anno.engine.serialization.SerializedProperty
import me.anno.engine.ui.render.RenderState
import me.anno.engine.ui.render.RenderView
import me.anno.gpu.CullMode
import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.FBStack
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.Texture2D
import me.anno.image.raw.FloatImage
import me.anno.image.raw.IFloatImage
import me.anno.input.Input
import me.anno.io.files.FileReference
import me.anno.io.files.Reference.getReference
import me.anno.language.translation.NameDesc
import me.anno.maths.Maths.ceilDiv
import me.anno.maths.Maths.clamp
import me.anno.maths.Maths.mix
import me.anno.tsunamis.draw.Drawing
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.CPUEngine.Companion.getPixels
import me.anno.tsunamis.engine.EngineType
import me.anno.tsunamis.engine.TsunamiEngine
import me.anno.tsunamis.engine.gpu.Compute16Engine
import me.anno.tsunamis.engine.gpu.ComputeEngine.Companion.scaleTextureRGBA32F
import me.anno.tsunamis.image.CompositeFloatImage
import me.anno.tsunamis.image.FloatBufferImage
import me.anno.tsunamis.io.ColorMap
import me.anno.tsunamis.io.NetCDFExport
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.ui.Style
import me.anno.ui.base.groups.PanelListY
import me.anno.ui.base.text.TextPanel
import me.anno.ui.editor.PropertyInspector
import me.anno.ui.editor.SettingCategory
import me.anno.utils.ShutdownException
import me.anno.utils.hpc.ProcessingGroup
import me.anno.utils.hpc.threadLocal
import me.anno.utils.structures.lists.Lists.firstInstanceOrNull
import org.apache.logging.log4j.LogManager
import org.joml.*
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * simple 3d mesh, which simulates water
 * */
class FluidSim : ProceduralMesh, CustomEditMode, OnUpdate {

    constructor()

    constructor(base: FluidSim) {
        base.copyInto(this)
    }

    var engineType: EngineType = EngineType.GPU_COMPUTE_FP16B16 // best performance, so default
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
    var bathymetryMesh: ProceduralMesh? = null

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

    @Group("Controls")
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
    @Suppress("unused")
    fun resetSimulation() {
        wantsReset = true
        computingThread?.interrupt()
        computingThread = null
        invalidateFluid()
        onRestart()
    }

    @DebugAction
    @Suppress("unused")
    fun setFluidZero() {
        val engine = engine ?: return
        computingThread?.interrupt()
        computingThread = null
        synchronized(workMutex) {
            engine.setZero()
        }
        invalidateFluid()
        onRestart()
    }

    private fun onRestart() {
        simulatedTime = 0.0
        lastSimulatedTime = 0.0
        simulationSpeed = 0f
    }

    @DebugAction
    @Docs("Export as NetCDF")
    fun exportNetCDF() {
        // export the current state as NetCDF
        // todo we could ask the user to enter a path
        if (ensureFieldSize()) {
            NetCDFExport.export(this, false)
            isPaused = true
            // update the ui to reflect that the simulation was stopped
            PropertyInspector.invalidateUI(true)
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

    @DebugProperty
    @NotSerializedProperty
    var simulationSpeed = 0f

    @DebugProperty
    val stepsPerSecond
        get() = simulationSpeed / maxTimeStep

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

    @Suppress("unused")
    fun getSurfaceHeightAt(x: Int, y: Int): Float {
        return engine!!.getSurfaceHeightAt(x, y)
    }

    @Suppress("unused")
    fun getFluidHeightAt(x: Int, y: Int): Float {
        return engine!!.getFluidHeightAt(x, y)
    }

    @Suppress("unused")
    fun getMomentumXAt(x: Int, y: Int): Float {
        return engine!!.getMomentumXAt(x, y)
    }

    @Suppress("unused")
    fun getMomentumYAt(x: Int, y: Int): Float {
        return engine!!.getMomentumYAt(x, y)
    }

    @Suppress("unused")
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
        val oldEngine = engine
        if (!GFX.isGFXThread()) {
            LOGGER.debug("Not GFX thread")
            return false
        }
        return if (oldEngine == null || oldEngine.width != width || oldEngine.height != height || wantsReset) {

            val setup = setup ?: return false
            if (!setup.isReady()) return false

            // reset engine
            // to make it cheaper, we could replace them...
            var newEngine = engineType.create(w, h)
            if (oldEngine != null && newEngine.isCompatible(oldEngine)) {
                newEngine = oldEngine // we can still use the old engine
            } else {
                this.engine = newEngine
            }

            wantsReset = false
            hasValidBathymetryMesh = false

            GFX.check()

            try {
                newEngine.init(this, setup, gravity, minFluidHeight)
            } catch (e: Exception) {
                // fallback
                e.printStackTrace()
                newEngine = CPUEngine(w, h)
                this.engine = newEngine
                newEngine.init(this, setup, gravity, minFluidHeight)
            }

            GFX.check()

            if (oldEngine != null && oldEngine !== newEngine) {
                if (newEngine.width == oldEngine.width &&
                    newEngine.height == oldEngine.height
                ) {
                    try {
                        if (oldEngine.supportsTexture()) {
                            GFX.check()
                            val fluidData = oldEngine.requestFluidTexture(w, h)
                            GFX.check()
                            newEngine.setFromTextureRGBA32F(fluidData)
                            GFX.check()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                oldEngine.destroy()
                GFX.check()
            }

            GFX.check()

            invalidateFluid()
            invalidateBathymetryMesh()

            timeStepIndex = 0
            maxTimeStep = computeMaxTimeStep()

            GFX.check()

            true
        } else true
    }

    fun initWithSetup(setup: FluidSimSetup): Boolean {
        this.setup = setup
        wantsReset = true
        return ensureFieldSize()
    }

    @NotSerializedProperty
    private var lastStep = 0L

    @NotSerializedProperty
    private var lastSimulatedTime = 0.0

    @NotSerializedProperty
    private var simulatedTime = 0.0

    private fun computeSimulationSpeed() {
        val time = Time.nanoTime
        val delta = time - lastStep
        lastStep = time
        val delta2 = simulatedTime - lastSimulatedTime
        if (delta2 > 0.0) {
            lastSimulatedTime = simulatedTime
            val mixFactor = 0.3f // temporal smoothing
            simulationSpeed = mix(simulationSpeed, (delta2 * 1e9 / delta).toFloat(), mixFactor)
        }
    }

    override fun onUpdate() {
        if (ensureFieldSize()) {
            val bm = bathymetryMesh
            if (!hasValidBathymetryMesh && bm != null) {
                generateBathymetryMesh(bm)
            }
            if (!isPaused) {
                val engine = engine!!
                val dt = Time.deltaTime.toFloat() * timeFactor
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
                                computeSimulationSpeed()
                            }
                        }
                    } else {
                        synchronized(workMutex) {
                            step(dt)
                        }
                        invalidateFluid()
                        computeSimulationSpeed()
                    }
                }
            }
        }
    }

    override fun generateMesh(mesh: Mesh) {
        if (ensureFieldSize()) {
            mesh.hasHighPrecisionNormals = true
            val engine = engine
            if (engine != null && engine.width == width && engine.height == height) {
                generateFluidMesh(this)
            } // else wait for simulation
        }
    }

    @SerializedProperty
    var flipBathymetryNormal = CullMode.BOTH
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

    @Group("Visuals")
    @SerializedProperty
    var fluidHalfTransparent = true
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
            }
        }

    /**
     * @param cw coarse width without ghost cells
     * @param ch coarse height without ghost cells
     * */
    private fun setMaterialProperties(
        material: Material,
        colorMap: ColorMap?,
        cellSize: Float,
        cw: Int, ch: Int
    ) {
        if (colorMap != null) {
            colorMap.createTexture(colorMapTexture, true, false) { _, err ->
                err?.printStackTrace()
            }
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
        val cellOffset = Vector3f(-cw * 0.5f * cellSize, 0f, -ch * 0.5f * cellSize)
        material["cellOffset"] = TypeValue(GLSLType.V3F, cellOffset)
        material["visualization"] = TypeValue(GLSLType.V1I, visualization.id)
        val visScale = 1f / max(1e-38f, maxVisualizedValueInternally)
        material["visScale"] = TypeValue(GLSLType.V1F, visScale)
        material["halfTransparent"] = TypeValue(GLSLType.V1B, fluidHalfTransparent)
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
        material["coarseSize"] = TypeValue(GLSLType.V2I, Vector2i(cw, ch))
        material["nearestNeighborColors"] = TypeValue(GLSLType.V1B, nearestNeighborColors)
    }

    // todo separate color map for bathymetry & fluid
    // todo nearest neighbor colors for bathymetry & meshes in general (?)

    @Group("Visuals")
    @SerializedProperty
    var nearestNeighborColors = false
        set(value) {
            if (field != value) {
                field = value
                invalidateFluid()
                // invalidateBathymetryMesh()
            }
        }

    /**
     * @param cw coarse width without ghost cells
     * @param ch coarse height without ghost cells
     * */
    private fun ensureTriangleCount(mesh: ProceduralMesh, cw: Int, ch: Int) {
        val mesh2 = mesh.getMesh()
        val targetSize = (cw - 1) * (ch - 1) * 2
        if (mesh2.proceduralLength != targetSize) {
            mesh2.proceduralLength = targetSize
            mesh2.positions = null
            mesh2.indices = null
            mesh2.normals = null
            mesh2.color0 = null
            mesh2.invalidateGeometry()
        }

        // set the bounding box positions, so it is clickable
        val mesh3 = collisionMesh
        val x = width * 0.5f * cellSizeMeters
        val z = height * 0.5f * cellSizeMeters
        mesh3.positions = floatArrayOf(
            -x, 0f, -z,
            -x, 0f, +z,
            +x, 0f, -z,
            +x, 0f, +z,
        )
        mesh3.indices = intArrayOf(
            0, 1, 2, 2, 3, 0,
            0, 2, 1, 2, 0, 2
        )
    }

    private val collisionMesh = Mesh()
    override fun raycastAnyHit(query: RayQuery): Boolean {
        val mesh = collisionMesh
        return if (RaycastMesh.raycastGlobalMeshAnyHit(query, transform, mesh)) {
            query.result.mesh = mesh
            true
        } else false
    }

    override fun raycastClosestHit(query: RayQuery): Boolean {
        val mesh = collisionMesh
        return if (RaycastMesh.raycastGlobalMeshClosestHit(query, transform, mesh)) {
            query.result.mesh = mesh
            true
        } else false
    }

    override fun fillSpace(globalTransform: Matrix4x3d, aabb: AABBd): Boolean {
        val dx = width * 0.5 * cellSizeMeters
        val dz = height * 0.5 * cellSizeMeters
        localAABB.setMin(-dx, 0.0, -dz).setMax(+dx, 0.0, +dz)
        localAABB.transform(globalTransform, globalAABB)
        aabb.union(globalAABB)
        return true
    }

    private fun generateFluidMesh(mesh: ProceduralMesh) {
        val engine = engine ?: return
        val cellSizeMeters = cellSizeMeters
        val colorMap = ColorMap.read(colorMap, false)
        val scale = max(1, coarsening)
        val w = width
        val h = height
        val cw = ceilDiv(width, scale)
        val ch = ceilDiv(height, scale)
        findVisualScale()
        val cellSize = getCellSize(cellSizeMeters, w, cw)
        if (useTextureData2) {
            val list = if (engine is Compute16Engine) fluidMaterialList16 else fluidMaterialList32
            mesh.materials = list
            val material = MaterialCache[list[0]]
            if (material != null) {
                if (engine is Compute16Engine) {
                    material["fluidSurface"] = TypeValue(GLSLType.S2D, engine.surface0)
                    material["fluidMomentumX"] = TypeValue(GLSLType.S2D, engine.momentumX)
                    material["fluidMomentumY"] = TypeValue(GLSLType.S2D, engine.momentumY)
                    material["fluidBathymetry"] = TypeValue(GLSLType.S2D, engine.bathymetryTex)
                } else {
                    val fluidTexture = engine.requestFluidTexture(cw, ch)
                    material["fluidData"] = TypeValue(GLSLType.S2D, fluidTexture)
                }
                setMaterialProperties(material, colorMap, cellSize, cw, ch)
            }
            ensureTriangleCount(mesh, cw, ch)
        } else {
            mesh.materials = emptyList()
            mesh.getMesh().proceduralLength = 0
            engine.createFluidMesh(
                w + 2, h + 2, cw + 2, ch + 2, cellSize, scale, fluidHeightScale,
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

    @SerializedProperty
    var computeBudgetFPS = 60f

    @SerializedProperty
    var maxIterationsPerFrame = 1000

    fun step(dt: Float, numMaxIterations: Int = maxIterationsPerFrame): Float {
        var done = 0f
        var i = 0
        val t0 = Time.nanoTime
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
                simulatedTime += step
                timeStepIndex++
            } else break
            val t1 = Time.nanoTime
            if ((t1 - t0) * computeBudgetFPS > 1e9f) break
            Thread.sleep(0) // for interrupts
        }
        engine.updateStatistics(this)
        return done
    }

    @SerializedProperty
    var synchronize = false

    var minFluidHeight = 0.01f

    fun computeStep(scaling: Float) {
        val engine = engine ?: return
        engine.step(gravity, scaling, minFluidHeight)
        if (synchronize) engine.synchronize()
    }

    @DebugProperty
    @NotSerializedProperty
    var maxVelocity = 0f

    private fun computeMaxTimeStep(): Float {
        val maxVelocity = engine!!.computeMaxVelocity(gravity, minFluidHeight)
        this.maxVelocity = maxVelocity
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
        val query = RayQuery(
            RenderState.cameraPosition,
            RenderView.currentInstance!!.mouseDirection,
            1e6, 0.0, 0.0,
            Raycast.TRIANGLES, -1, false, emptySet()
        )
        if (Raycast.raycastClosestHit(entity!!, query)) {
            val result = query.result
            val transform = transform!!
            val global2Local = transform.globalTransform.invert(Matrix4x3d())
            val localPosition = global2Local.transformPosition(result.positionWS, Vector3d())
            val centerX = width * 0.5f
            val centerY = height * 0.5f
            // the local grid positions
            val cellX = (localPosition.x / cellSizeMeters).toFloat() + centerX
            val cellY = (localPosition.z / cellSizeMeters).toFloat() + centerY
            dst.set(cellX, cellY, result.distance.toFloat())
        } else dst.z = -1f
        return dst
    }

    @Group("Drawing")
    @Range(0.0, 1e10)
    @SerializedProperty
    var brushSize = 30f * 250f

    @Group("Drawing")
    @Range(0.0, 1e10)
    @SerializedProperty
    var brushStrength = 5f

    fun invalidateSetup() {
        if (computeOnly) return
        computingThread?.interrupt()
        computingThread = null
        wantsReset = true
        invalidateFluid()
        invalidateBathymetryMesh()
    }

    fun invalidateFluid() {
        if (computeOnly) return
        if (GFX.isGFXThread()) {
            if (useTextureData2) {
                GFX.check()
                generateFluidMesh(this)
            }
            GFX.check()
            invalidateMesh()
            GFX.check()
            // ensureBuffer()
            // GFX.check()
        } else {
            GFX.addGPUTask("invalidate", 1) {
                invalidateFluid()
            }
        }
    }

    override fun onEditMove(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        if (Input.isLeftDown) {
            // critical velocity: sqrt(g*h), so make it that we draw that within 10s
            val time = Time.nanoTime
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
                    lastChange = Time.nanoTime
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

    override fun createInspector(list: PanelListY, style: Style, getGroup: (nameDesc: NameDesc) -> SettingCategory) {
        super.createInspector(list, style, getGroup)
        val title = list.listOfAll.firstOrNull { it is TextPanel && it.text == "Color Map Scale" } as? TextPanel
        if (title != null) {
            val group = title.listOfHierarchyReversed.firstInstanceOrNull(PanelListY::class)
            if (group != null) {
                var indexInList = 0
                title.listOfHierarchy {
                    if (it.parent === group) {
                        indexInList = it.indexInParent
                    }
                }
                group.add(indexInList, TextPanel("Color Map:", style))
                group.add(indexInList + 1, ColorMapPreview(this, style))
            } else LOGGER.warn("Group not found!")
        } else LOGGER.warn("Title was not found!")
    }

    override fun clone() = FluidSim(this)

    override fun copyInto(dst: PrefabSaveable) {
        super.copyInto(dst)
        dst as FluidSim
        // copy the scalar properties
        dst.width = width
        dst.height = height
        dst.cellSizeMeters = cellSizeMeters
        dst.gravity = gravity
        dst.cfl2d = cfl2d
        dst.timeFactor = timeFactor
        /*// copy the array properties
        clone.fluidHeight = copy(fluidHeight)
        clone.fluidMomentumX = copy(fluidMomentumX)
        clone.fluidMomentumY = copy(fluidMomentumY)
        clone.bathymetry = copy(bathymetry)
        val size = fluidHeight.size
        clone.tmpH = FloatArray(size)
        clone.tmpHuX = FloatArray(size)
        clone.tmpHuY = FloatArray(size)*/
        // dst.needsUpdate = needsUpdate
        dst.wantsReset = wantsReset
        dst.colorMap = colorMap
        dst.computeTimeStepInterval = computeTimeStepInterval
        // copy the references
        dst.setup = getInClone(setup, dst)
        dst.bathymetryMesh = getInClone(bathymetryMesh, dst)
    }

    override fun destroy() {
        super.destroy()
        getMesh().destroy()
        computingThread?.interrupt()
        computingThread = null
        engine?.destroy()
        engine = null
    }

    override val className: String = "Tsunamis/FluidSim"
    // val solidMaterialList by lazy { createMaterials() }

    fun isApprox(a: Int, b: Int, threshold: Float = 1.05f): Boolean {
        return min(a, b) * threshold >= max(a, b)
    }

    /**
     * you can request this data once every n frames to adjust fluid game mechanics
     * @param mustBeCopy forces a full copy of the data, only applies to CPUEngine, as the others have to be copied anyways
     * @return resulting data with the channels (fluid height, momentum x, momentum y, bathymetry), without ghost cells, or null if engine is null
     * */
    fun requestFluidData(w: Int = width, h: Int = height, mustBeCopy: Boolean = false): IFloatImage? {
        val engine = engine ?: return null
        val width = engine.width
        val height = engine.height
        // todo better filtering (or at least options for that)
        return when (engine::class) {
            CPUEngine::class -> {
                engine as CPUEngine
                if (mustBeCopy || !isApprox(w, width) || !isApprox(h, height)) {
                    val newData = FloatArray(w * h * 4)
                    val fh = engine.fluidHeight
                    val hu = engine.fluidMomentumX
                    val hv = engine.fluidMomentumY
                    val ba = engine.bathymetry
                    var i4 = 0
                    for (y in 0 until  h) {
                        for (x in 0 until w) {
                            val fineIndex = coarseIndexToFine2d(width, height, w, h, x,y)
                            newData[i4] = fh[fineIndex]
                            newData[i4 + 1] = hu[fineIndex]
                            newData[i4 + 2] = hv[fineIndex]
                            newData[i4 + 3] = ba[fineIndex]
                            i4 = 4
                        }
                    }
                    FloatImage(w, h, 4, newData)
                } else {
                    // includes ghost cells, which might cause slight issues...
                    // you can set mustBeCopy to prevent that
                    CompositeFloatImage(
                        width + 2, height + 2,
                        arrayOf(engine.fluidHeight, engine.fluidMomentumX, engine.fluidMomentumY, engine.bathymetry),
                    )
                }
            }
            else -> {
                val tex = engine.requestFluidTexture(w, h)
                val tex2 = if (tex.width > w || tex.height > h) {
                    val buffer = FBStack["fluidSim", w, h, 4, true, 1, DepthBufferType.NONE]
                    buffer.ensure()
                    scaleTextureRGBA32F(tex, buffer.getTexture0() as Texture2D)
                } else tex
                // get the pixels from the image
                val (pixels, _) = getPixels(tex2)
                FloatBufferImage(tex2.width, tex2.height, 4, pixels)
            }
        }
    }

    companion object {

        /**
         * scales down a 1d index from a coarse grid to a fine grid, preserves the border;
         * w and cw include the ghost cells
         * */
        fun coarseIndexToFine1d(x: Int, w: Int, cw: Int): Int {
            return when {
                x < 2 -> x
                cw - x <= 2 -> x + (w - cw)
                cw <= 4 -> x
                else -> 2 + (x - 2) * (w - 4) / (cw - 4)
            }
        }

        /**
         * scales down a 2d index from a coarse grid to a fine grid, preserves the border;
         * w and cw include the ghost cells
         * @param w width with ghost cells
         * @param h height with ghost cells
         * @param cw coarse width with ghost cells
         * @param ch coarse height with ghost cells
         * */
        fun coarseIndexToFine2d(w: Int, h: Int, cw: Int, ch: Int, x: Int, y: Int): Int {
            val nx = coarseIndexToFine1d(x, w, cw)
            val ny = coarseIndexToFine1d(y, h, ch)
            return nx + ny * w
        }

        val fluidMaterialList16 = createMaterials(true)
        val fluidMaterialList32 = createMaterials(false)

        private fun createMaterials(halfPrecision: Boolean): List<FileReference> {
            val material = Material()
            material.shader = if (halfPrecision) YTextureShader.r16fShader
            else YTextureShader.rgbaShader
            return listOf(material.ref)
        }

        val threadPool = ProcessingGroup("TsunamiSim", 1f)
        val tmpV4ByThread = threadLocal { FloatArray(4) }
        private val defaultColorMap = getReference("res://colormaps/globe.xml")
        private val LOGGER = LogManager.getLogger(FluidSim::class)
        val f0 = FloatArray(0)

    }

}