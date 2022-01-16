package me.anno.tsunamis.draw

import me.anno.input.Input
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.TsunamiEngine.Companion.getMaxValue
import me.anno.tsunamis.engine.CPUEngine
import me.anno.utils.hpc.HeavyProcessing
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

    fun drawLineSegment(
        cmp: Vector3f, lmp: Vector3f,
        sim: FluidSim
    ) {
        // convert global hit coordinates into local space
        // convert brush size into local coordinates
        // compute the area of effect
        val brushSize = sim.brushSize / sim.cellSizeMeters
        val cellMinX = max((min(cmp.x, lmp.x) - brushSize).toInt(), 0)
        val cellMaxX = min((max(cmp.x, lmp.x) + brushSize).toInt(), sim.width - 1)
        val cellMinY = max((min(cmp.y, lmp.y) - brushSize).toInt(), 0)
        val cellMaxY = min((max(cmp.y, lmp.y) + brushSize).toInt(), sim.height - 1)
        // compute brush strength
        val brushStrength = (if (Input.isShiftDown) -1f else +1f) * sim.brushStrength
        // apply brush with circular falloff
        val invBrushSize = 1f / brushSize
        val minPerThread = 4000 / (cellMaxX - cellMinX)
        val engine = sim.engine as CPUEngine
        HeavyProcessing.processBalanced(cellMinY, cellMaxY + 1, minPerThread) { y0, y1 ->
            // todo shorten line by 1px, so we don't draw that pixel twice?
            // todo or draw using a spline instead?
            val vx = cmp.x
            val vy = cmp.y
            val wx = lmp.x
            val wy = lmp.y
            val dwx = wx - vx
            val dwy = wy - vy
            val lineLengthSquared = dwx * dwx + dwy * dwy
            val dwx2 = dwx / lineLengthSquared
            val dwy2 = dwy / lineLengthSquared
            val fluidHeight = engine.fluidHeight
            for (yi in y0 until y1) {
                var index = sim.getIndex(cellMinX, yi)
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
                        val d2 = (dpx * dpx + dpy * dpy)
                        val strength = brushStrength * getNormalizedBrushShape(d2 * invBrushSize)
                        fluidHeight[index] += strength * dpx
                        fluidHeight[index] += strength * dpy
                    }
                    index++
                }
            }
        }

        // if paused & maxVisualizedValue == 0f, then update min/max
        if (sim.isPaused && sim.maxVisualizedValue == 0f) {
            sim.maxSurfaceHeight = getMaxValue(sim.width, sim.height, sim.coarsening, engine.fluidHeight, engine.bathymetry)
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