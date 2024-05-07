package me.anno.tsunamis.setups

import me.anno.ecs.annotations.Docs
import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.engine.serialization.SerializedProperty
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ParabolaSetup : FluidSimSetup() {

    @Docs("Pool depth in meters, must be > 0")
    @Range(0.0, Double.POSITIVE_INFINITY)
    @SerializedProperty
    var poolDepth = 100f

    @SerializedProperty
    var displacementHeight = 5f

    @Docs("Relative size of displacement in wave traversal direction")
    @SerializedProperty
    @Range(0.0, 10.0)
    var displacementFractionX = 0.2f

    @Docs("Relative size of displacement along the wave extrema")
    @SerializedProperty
    @Range(0.0, 10.0)
    var displacementFractionZ = 0.2f

    @SerializedProperty
    var maxBathymetry = 10f

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        return max(0f, -getBathymetry(x, y, w, h))
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        val wm1 = w - 1
        val hm1 = h - 1
        val dx = (x * 2 - wm1).toFloat()
        val dy = (y * 2 - hm1).toFloat()
        val sq = dx * dx / max(1, wm1 * wm1) + dy * dy / max(1, hm1 * hm1)
        return min((sq - 1f) * poolDepth, maxBathymetry)
    }

    override fun getDisplacement(x: Int, y: Int, w: Int, h: Int): Float {
        val wm1 = w - 1
        val hm1 = h - 1
        val lx = (x * 2 - wm1) / (displacementFractionX * max(1, wm1))
        val ly = (y * 2 - hm1) / (displacementFractionZ * max(1, hm1))
        return if (lx in -1f..1f && ly in -1f..1f) {
            val f = -sin(lx * PI.toFloat())
            val g = 1 - ly * ly
            displacementHeight * f * g
        } else 0f
    }

    override fun clone(): ParabolaSetup {
        val clone = ParabolaSetup()
        copyInto(clone)
        return clone
    }

    override fun copyInto(dst: PrefabSaveable) {
        super.copyInto(dst)
        dst as ParabolaSetup
        dst.poolDepth = poolDepth
        dst.displacementHeight = displacementHeight
        dst.displacementFractionX = displacementFractionX
        dst.displacementFractionZ = displacementFractionZ
        dst.maxBathymetry = maxBathymetry
    }

    override val className: String = "Tsunamis/ParabolaSetup"

}