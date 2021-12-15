package me.anno.tsunamis.setups

import me.anno.ecs.prefab.PrefabSaveable
import me.anno.io.serialization.SerializedProperty
import kotlin.math.max

class LinearDiscontinuity : FluidSimSetup {

    constructor()

    constructor(base: LinearDiscontinuity) {
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

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        val surface = if (x * 2 >= w) heightRight else heightLeft
        return max(surface - getBathymetry(x, y, w, h), 0f)
    }

    override fun getMomentumX(x: Int, y: Int, w: Int, h: Int): Float {
        return if (x * 2 >= w) impulseRight else impulseLeft
    }

    override fun fillBathymetry(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun clone() = LinearDiscontinuity(this)

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as LinearDiscontinuity
        clone.heightLeft = heightLeft
        clone.heightRight = heightRight
        clone.impulseLeft = impulseLeft
        clone.impulseRight = impulseRight
    }

    override val className: String = "Tsunamis/LinearDiscontinuity"

}