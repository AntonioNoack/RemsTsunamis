package me.anno.tsunamis.engine.gpu


class Operation(
    val startValue: String,
    val function: String
) {
    companion object {
        val MAX = Operation("1e-38","max(a,b)")
        val MIN = Operation("1e38","min(a,b)")
        val SUM = Operation("0.0","a+b")
        val MAX_ABS = Operation("0.0","max(abs(a),abs(b))")
        val MAX_RA = Operation("0.0","max(a, vec4(b.x > 0.0 ? abs(b.x + b.w) : 0.0, b.yz, 0.0))")
    }
}

