package me.anno.tsunamis.setups

import me.anno.ecs.Component

open class FluidSimSetup : Component() {

    open fun getHeight(x: Int, y: Int, w: Int, h: Int) = 1f

    open fun getBathymetry(x: Int, y: Int, w: Int, h: Int) = 0f

    open fun getMomentumX(x: Int, y: Int, w: Int, h: Int) = 0f

    open fun getMomentumY(x: Int, y: Int, w: Int, h: Int) = 0f

    override fun clone(): FluidSimSetup {
        val clone = FluidSimSetup()
        copy(clone)
        return clone
    }

}