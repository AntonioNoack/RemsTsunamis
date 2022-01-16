package me.anno.tsunamis.setups

import me.anno.ecs.annotations.DebugProperty
import me.anno.ecs.annotations.Docs
import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.maths.Maths.mix
import me.anno.maths.Maths.pow
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class CriticalFlowSetup : FluidSimSetup() {

    @Range(0.0, Double.POSITIVE_INFINITY)
    var shallowDepth = 1.8f

    @Range(0.0, Double.POSITIVE_INFINITY)
    var baseDepth = 2f

    var momentumX = 4.42f

    @Range(0.0, 1.0)
    var bowWidth = 0.5f

    @Range(0.0, Double.POSITIVE_INFINITY)
    var bowPower = 2f

    /**
     * froude number = u / sqrt(g*h),
     * momentum hu is constant at t=0
     * froude number = (g^-0.5) * hu / (h^1.5)
     *  = constant / (h^1.5)
     * therefore the froude number is maximal, where the water height is minimal
     *
     * F < 1 -> subcritical
     * F ~ 1 -> critical
     * F > 1 -> supercritical
     * */
    @Docs("maximum froude number at t=0 with default gravity of 9.81m/s²")
    @DebugProperty
    val initialMaximumFroudeNumber: Float
        get() {
            // could be changed by the simulation itself; we don't really have access to the gravity here
            val gravity = 9.81f
            return momentumX / sqrt(gravity * min(shallowDepth, baseDepth))
        }

    override fun getPreferredNumCellsX() = 200

    override fun getPreferredNumCellsY() = 1

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        val relativePosition = abs(x * 2f / w - 1f) // [-1,+1] -> [1,0],[0,1]
        return if (relativePosition > bowWidth) baseDepth
        else {
            val bowFraction = relativePosition / bowWidth
            mix(shallowDepth, baseDepth, pow(bowFraction, bowPower))
        }
    }

    override fun getBathymetry(x: Int, y: Int, w: Int, h: Int): Float {
        return -getHeight(x, y, w, h)
    }

    override fun getMomentumX(x: Int, y: Int, w: Int, h: Int): Float {
        return momentumX
    }

    override fun fillMomentumX(w: Int, h: Int, dst: FloatArray) {
        dst.fill(momentumX)
    }

    override fun fillMomentumY(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun clone(): FluidSimSetup {
        val clone = CriticalFlowSetup()
        copy(clone)
        return clone
    }

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as CriticalFlowSetup
        clone.shallowDepth = shallowDepth
        clone.baseDepth = baseDepth
        clone.bowWidth = bowWidth
        clone.bowPower = bowPower
    }

    override val className: String = "Tsunamis/CriticalFlowSetup"

}