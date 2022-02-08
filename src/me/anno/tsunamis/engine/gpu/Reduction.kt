package me.anno.tsunamis.engine.gpu

import me.anno.gpu.shader.Reduction
import kotlin.math.abs

/** finds the maximum amplitude of the surface where water is, plus max absolute momentum */
val MAX_RA = Reduction.Operation(
    "max-ra", 0f,
    "max(a, vec4(b.x > 0.0 ? abs(b.x + b.w) : 0.0, b.yz, 0.0))",
    false
) { dst, input ->
    input.set(if (input.x > 0f) input.x + input.w else 0f, abs(input.y), abs(input.z), 0f)
    dst.max(input)
}