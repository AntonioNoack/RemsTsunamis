package me.anno.tsunamis.draw

import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.input.Input
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.TsunamiEngine.Companion.getMaxValue
import me.anno.tsunamis.engine.gpu.ComputeEngine
import me.anno.tsunamis.engine.gpu.GraphicsEngine
import me.anno.utils.LOGGER
import me.anno.utils.hpc.HeavyProcessing
import org.joml.Vector2i
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

object Drawing {

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

    val drawShader by lazy {
        ComputeShader(
            "drawing", Vector2i(16), "" +
                    "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                    "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                    "uniform ivec2 inSize;\n" +
                    "uniform ivec2 offset;\n" +
                    "uniform vec2 v, dw, dw2;\n" +
                    "uniform float brushStrength;\n" +
                    "float slerp(float x){\n" +
                    "   return x*x*(3.0-2.0*x);\n" +
                    "}\n" +
                    "float getNormalizedBrushShape(float d2){\n" +
                    "   return d2 < 1.0 ? slerp(1.0 - d2) : 0.0;\n" +
                    "}\n" +
                    "void main(){\n" +
                    "   ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(p.x < inSize.x && p.y < inSize.y){\n" +
                    "       vec2 dp = vec2(p) - v;\n" +
                    "       float t = dot(dp, dw);\n" +
                    "       if(t >= 0.0 && t < 1.0){\n" +
                    "           dp -= t * dw;\n" +
                    "           float d2 = dot(dp, dp);\n" +
                    "           float strength = brushStrength * getNormalizedBrushShape(d2);\n" +
                    "           ivec2 uv = p + offset;\n" +
                    "           vec4 newData = imageLoad(src, uv);\n" +
                    "           newData.x = max(0.0, newData.x + strength * (dp.y * dw2.x - dp.x * dw2.y));\n" +
                    "           imageStore(dst, uv, newData);\n" +
                    "       }\n" +
                    "   }\n" +
                    "}"
        )
    }

    val copyShader by lazy {
        ComputeShader(
            "drawing-copy", Vector2i(16), "" +
                    "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                    "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                    "uniform ivec2 inSize;\n" +
                    "uniform ivec2 offset;\n" +
                    "void main(){\n" +
                    "   ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(p.x < inSize.x && p.y < inSize.y){\n" +
                    "       ivec2 uv = p + offset;\n" +
                    "       imageStore(dst, uv, imageLoad(src, uv));\n" +
                    "   }\n" +
                    "}"
        )
    }

    fun drawLineSegment(
        cmp: Vector3f, lmp: Vector3f,
        sim: FluidSim
    ) {
        // convert global hit coordinates into local space
        // convert brush size into local coordinates
        // compute the area of effect
        val brushSize = sim.brushSize / sim.cellSizeMeters
        val cellMinX = max((min(cmp.x, lmp.x) - brushSize).toInt(), 0)
        val cellMaxX = min((max(cmp.x, lmp.x) + brushSize).toInt() + 1, sim.width)
        val cellMinY = max((min(cmp.y, lmp.y) - brushSize).toInt(), 0)
        val cellMaxY = min((max(cmp.y, lmp.y) + brushSize).toInt() + 1, sim.height)
        // compute brush strength
        val brushStrength = (if (Input.isShiftDown) -1f else +1f) * sim.brushStrength
        // apply brush with circular falloff
        val invBrushSize = 1f / brushSize
        val vx = cmp.x
        val vy = cmp.y
        val wx = lmp.x
        val wy = lmp.y
        val dwx = wx - vx
        val dwy = wy - vy
        val lineLengthSquared = dwx * dwx + dwy * dwy
        val dwx2 = dwx / lineLengthSquared
        val dwy2 = dwy / lineLengthSquared
        when (val engine = sim.engine) {
            is ComputeEngine, is GraphicsEngine -> {
                val src: Texture2D = (engine as? ComputeEngine)?.src ?: (engine as GraphicsEngine).src.getColor0()
                val tmp: Texture2D = (engine as? ComputeEngine)?.tmp ?: (engine as GraphicsEngine).tmp.getColor0()
                var shader = drawShader
                shader.use()
                shader.v2i("inSize", cellMaxX - cellMinX, cellMaxY - cellMinY)
                shader.v1f("brushStrength", brushStrength)
                shader.v2f("dw", dwx, dwy)
                shader.v2f("dw2", dwx2, dwy2)
                shader.v2f("v", vx - cellMinX, vy - cellMinY)
                shader.v2i("offset", cellMinX, cellMinY)
                ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
                ComputeShader.bindTexture(1, tmp, ComputeTextureMode.WRITE)
                shader.runBySize(cellMaxX - cellMinX, cellMaxY - cellMinY)
                shader = copyShader
                shader.use()
                shader.v2i("inSize", cellMaxX - cellMinX, cellMaxY - cellMinY)
                shader.v2i("offset", cellMinX, cellMinY)
                ComputeShader.bindTexture(0, tmp, ComputeTextureMode.READ)
                ComputeShader.bindTexture(1, src, ComputeTextureMode.WRITE)
                shader.runBySize(cellMaxX - cellMinX, cellMaxY - cellMinY)
            }
            is CPUEngine -> {
                val minPerThread = 4000 / (cellMaxX - cellMinX)
                HeavyProcessing.processBalanced(cellMinY, cellMaxY, minPerThread) { y0, y1 ->
                    // todo shorten line by 1px, so we don't draw that pixel twice?
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
                                dpx -= t * dwx
                                dpy -= t * dwy
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
            else -> {
                LOGGER.warn("Drawing not supported on this engine")
            }
        }

        // something has changed, to update the mesh
        sim.invalidateFluid()

    }

    private fun slerp(x: Float): Float {
        return x * x * (3 - 2 * x)
    }

    private fun getNormalizedBrushShape(d2: Float): Float {
        return if (d2 < 1f) slerp(1f - d2) else 0f
    }

}