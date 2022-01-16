package me.anno.tsunamis.setups

import me.anno.ecs.annotations.Range
import me.anno.ecs.prefab.PrefabSaveable
import me.anno.maths.Maths.clamp
import me.anno.maths.Maths.length
import me.anno.maths.Maths.mix
import org.joml.Vector2f.lengthSquared
import kotlin.math.max

class CircularDiscontinuity : FluidSimSetup {

    constructor()

    constructor(base: CircularDiscontinuity) {
        base.copy(this)
    }

    var heightInner = 8f
    var heightOuter = 10f
    var impulseInner = 0f
    var impulseOuter = 0f

    var withAntialiasing = true

    @Range(-0.5, Double.POSITIVE_INFINITY)
    var radius = 0f

    private fun getFactor(x: Int, y: Int, w: Int, h: Int): Float {
        if (radius <= 0f) radius = (w + h) / 16f
        val radius = radius
        return if (withAntialiasing) {
            val distance = length(x - w * 0.5f, y - h * 0.5f)
            clamp(radius - distance + 0.5f, 0f, 1f)
        } else {
            val d2 = lengthSquared(x - w * 0.5f, y - h * 0.5f)
            if (d2 < radius * radius) 0f else 1f
        }
    }

    override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
        val surface = mix(heightInner, heightOuter, getFactor(x, y, w, h))
        return max(surface - getBathymetry(x, y, w, h), 0f)
    }

    override fun getMomentumX(x: Int, y: Int, w: Int, h: Int): Float {
        return mix(impulseInner, impulseOuter, getFactor(x, y, w, h))
    }

    override fun getMomentumY(x: Int, y: Int, w: Int, h: Int): Float {
        return mix(impulseInner, impulseOuter, getFactor(x, y, w, h))
    }

    override fun fillBathymetry(w: Int, h: Int, dst: FloatArray) {
        dst.fill(0f)
    }

    override fun clone() = CircularDiscontinuity(this)

    override fun copy(clone: PrefabSaveable) {
        super.copy(clone)
        clone as CircularDiscontinuity
        clone.heightInner = heightInner
        clone.heightOuter = heightOuter
        clone.impulseInner = impulseInner
        clone.impulseOuter = impulseOuter
        clone.radius = radius
        clone.withAntialiasing = withAntialiasing
    }

    override val className: String = "Tsunamis/CircularDiscontinuity"

}