package me.anno.tsunamis.setups

import me.anno.ecs.prefab.PrefabSaveable
import me.anno.io.serialization.SerializedProperty
import me.anno.tsunamis.FluidSim
import kotlin.math.max

class LinearDiscontinuitySetup : FluidSimSetup {

    constructor()

    constructor(base: LinearDiscontinuitySetup) {
        base.copy(this)
    }

    @SerializedProperty
    var heightLeft = 8f

    @SerializedProperty
    var heightRight = 10f

    @SerializedProperty
    var impulseLeft = 0f

    @SerializedProperty
    var impulseRight = 0f

    @SerializedProperty
    var bathymetryLeft = 0f

    @SerializedProperty
    var bathymetryRight = 0f

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        val surface = if (x * 2 >= w) heightRight else heightLeft
        return max(surface - getBathymetry(x, y, w, h), 0f)
    }

    override fun getMomentumX(x: Int, y: Int, w: Int, h: Int): Float {
        return if (x * 2 >= w) impulseRight else impulseLeft
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        return if (x * 2 >= w) bathymetryRight else bathymetryLeft
    }

    override fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        FluidSim.threadPool.processBalanced(0, dst.size, 65536) { i0, i1 ->
            dst.fill(0f, i0, i1)
        }
    }

    override fun clone() = LinearDiscontinuitySetup(this)

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as LinearDiscontinuitySetup
        clone.heightLeft = heightLeft
        clone.heightRight = heightRight
        clone.impulseLeft = impulseLeft
        clone.impulseRight = impulseRight
    }

    override val className: String = "Tsunamis/LinearDiscontinuitySetup"

    override fun toString() = "LinearDiscontinuitySetup($heightLeft, $heightRight, $impulseLeft, $impulseRight, $bathymetryLeft, $bathymetryRight)"

}