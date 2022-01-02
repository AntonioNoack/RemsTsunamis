package me.anno.tsunamis.setups

import me.anno.ecs.annotations.Docs
import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.io.serialization.SerializedProperty
import kotlin.math.PI
import kotlin.math.sin

class PoolSetup : FluidSimSetup() {

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

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        return poolDepth
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        return -poolDepth
    }

    override fun fillHeight(w: Int, h: Int, dst: FloatArray) {
        dst.fill(poolDepth)
    }

    override fun getDisplacement(x: Int, y: Int, w: Int, h: Int): Float {
        val lx = (x - w * 0.5f) * 2f / (displacementFractionX * w)
        val ly = (y - h * 0.5f) * 2f / (displacementFractionZ * h)
        return if (lx in -1f..1f && ly in -1f..1f) {
            val f = -sin(lx * PI.toFloat())
            val g = 1 - ly * ly
            displacementHeight * f * g
        } else 0f
    }

    override fun clone(): PoolSetup {
        val clone = PoolSetup()
        copy(clone)
        return clone
    }

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as PoolSetup
        clone.poolDepth = poolDepth
        clone.displacementHeight = displacementHeight
        clone.displacementFractionX = displacementFractionX
    }

    override val className: String = "Tsunamis/PoolSetup"

}