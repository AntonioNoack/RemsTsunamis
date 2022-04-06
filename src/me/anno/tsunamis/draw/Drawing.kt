package me.anno.tsunamis.draw

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.input.Input
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.TsunamiEngine.Companion.getMaxValue
import me.anno.tsunamis.engine.gpu.Compute16Engine
import me.anno.tsunamis.engine.gpu.GPUEngine
import me.anno.utils.LOGGER
import me.anno.utils.hpc.HeavyProcessing
import org.joml.Vector3f
import org.lwjgl.opengl.GL30C.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Drawing {

    val rgbaShaders = DrawShaders(comp16engine = false, halfPrecisionBath = false)
    private val r16b16 = DrawShaders(comp16engine = true, halfPrecisionBath = true)
    private val r16b32 = DrawShaders(comp16engine = true, halfPrecisionBath = false)

    fun drawLineSegment(
        cmp: Vector3f, lmp: Vector3f,
        sim: FluidSim
    ) {
        // convert global hit coordinates into local space
        // convert brush size into local coordinates
        // compute the area of effect
        val brushSize = sim.brushSize / sim.cellSizeMeters
        val vx = cmp.x
        val vy = cmp.y
        val wx = lmp.x
        val wy = lmp.y
        val dwx1 = wx - vx
        val dwy1 = wy - vy
        val lineLengthSquared = dwx1 * dwx1 + dwy1 * dwy1
        val sqrt = sqrt(lineLengthSquared)
        // only extend brush area 90° rotated to its movement direction
        val deltaX = abs(+dwy1 * brushSize / sqrt)
        val deltaY = abs(-dwx1 * brushSize / sqrt)
        val cellMinX = max((min(vx, wx) - deltaX).toInt(), 0)
        val cellMaxX = min((max(vx, wx) + deltaX).toInt() + 1, sim.width)
        val cellMinY = max((min(vy, wy) - deltaY).toInt(), 0)
        val cellMaxY = min((max(vy, wy) + deltaY).toInt() + 1, sim.height)
        // apply brush with circular falloff
        val invBrushSize = 1f / brushSize
        val dwx2 = dwx1 / lineLengthSquared
        val dwy2 = dwy1 / lineLengthSquared
        // compute brush strength
        val brushStrength = (if (Input.isShiftDown) -1f else +1f) * sim.brushStrength * sqrt(lineLengthSquared)
        when (val engine = sim.engine) {
            is GPUEngine<*> -> {
                // this casting is currently correct, but may become incorrect with more engines
                val src = engine.src as? Texture2D ?: (engine.src as Framebuffer).getTexture0() as Texture2D
                val tmp = engine.tmp as? Texture2D ?: (engine.tmp as Framebuffer).getTexture0() as Texture2D
                drawLineSegment(
                    src, tmp, null, 0,
                    rgbaShaders, GL_RGBA32F,
                    cellMinX, cellMaxX,
                    cellMinY, cellMaxY,
                    brushStrength,
                    invBrushSize,
                    dwx1, dwy1,
                    dwx2, dwy2,
                    vx, vy
                )
            }
            is Compute16Engine -> {
                drawLineSegment(
                    engine.surface0, engine.surface1, engine.bathymetryTex,
                    if (engine.bathymetryFp16) GL_R16F else GL_R32F,
                    if (engine.bathymetryFp16) r16b16 else r16b32, GL_R16F,
                    cellMinX, cellMaxX,
                    cellMinY, cellMaxY,
                    brushStrength,
                    invBrushSize,
                    dwx1, dwy1,
                    dwx2, dwy2,
                    vx, vy
                )
                engine.invalidate()
            }
            is CPUEngine -> {
                val minPerThread = 4000 / (cellMaxX - cellMinX)
                HeavyProcessing.processBalanced(cellMinY, cellMaxY, minPerThread) { y0, y1 ->
                    // shorten line by 1px, so we don't draw that pixel twice?
                    // todo or draw using a spline instead?
                    val fluidHeight = engine.fluidHeight
                    for (yi in y0 until y1) {
                        var index = sim.getIndex(cellMinX, yi)
                        for (xi in cellMinX until cellMaxX) {
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
                                dpx -= t * dwx1
                                dpy -= t * dwy1
                                val d2 = (dpx * dpx + dpy * dpy)
                                val strength = brushStrength * getNormalizedBrushShape(d2 * invBrushSize)
                                fluidHeight[index] += strength * (dpy * dwx2 - dpx * dwy2)
                            }
                            index++
                        }
                    }
                }

                // if paused & maxVisualizedValue == 0f, then update min/max
                if (sim.isPaused && sim.maxVisualizedValue == 0f) {
                    sim.maxSurfaceHeight =
                        getMaxValue(sim.width, sim.height, sim.coarsening, engine.fluidHeight, engine.bathymetry)
                }
            }
            null -> {}
            else -> LOGGER.warn("Drawing not supported on ${engine.javaClass.simpleName}")
        }

        // something has changed, to update the mesh
        sim.invalidateFluid()

    }

    private fun slerp(x: Float): Float {
        return x * x * (3f - 2f * x)
    }

    private fun getNormalizedBrushShape(d2: Float): Float {
        return if (d2 < 1f) slerp(1f - d2) else 0f
    }

    fun drawLineSegment(
        src: Texture2D, tmp: Texture2D,
        bath: Texture2D?, bathFormat: Int,
        shaders: DrawShaders,
        format: Int,
        cellMinX: Int, cellMaxX: Int,
        cellMinY: Int, cellMaxY: Int,
        brushStrength: Float,
        invBrushSize: Float,
        dwx1: Float, dwy1: Float,
        dwx2: Float, dwy2: Float,
        vx: Float, vy: Float
    ) {
        var shader = shaders.drawShader
        val width = cellMaxX - cellMinX
        val height = cellMaxY - cellMinY
        GFX.check()
        shader.use()
        GFX.check()
        shader.v2i("inSize", width, height)
        shader.v2i("offset", cellMinX, cellMinY)
        shader.v2f("v", vx, vy)
        shader.v2f("brush", brushStrength, invBrushSize)
        shader.v4f("dw", dwx1, dwy1, dwx2, dwy2)
        GFX.check()
        ComputeShader.bindTexture(0, src, ComputeTextureMode.READ, format)
        ComputeShader.bindTexture(1, tmp, ComputeTextureMode.WRITE, format)
        if (bath != null) {
            ComputeShader.bindTexture(2, bath, ComputeTextureMode.WRITE, bathFormat)
        }
        GFX.check()
        shader.runBySize(width, height)
        GFX.check()
        shader = shaders.copyShader
        shader.use()
        shader.v2i("inSize", width, height)
        shader.v2i("offset", cellMinX, cellMinY)
        GFX.check()
        ComputeShader.bindTexture(0, tmp, ComputeTextureMode.READ, format)
        ComputeShader.bindTexture(1, src, ComputeTextureMode.WRITE, format)
        GFX.check()
        shader.runBySize(width, height)
        GFX.check()
    }

}