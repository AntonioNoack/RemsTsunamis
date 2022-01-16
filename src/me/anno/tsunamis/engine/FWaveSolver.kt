package me.anno.tsunamis.engine

import me.anno.image.ImageWriter
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.tsunamis.setups.LinearDiscontinuity
import org.apache.logging.log4j.LogManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * solves the shallow water equations,
 * also applies boundary conditions on dry-wet-boundaries
 * */
object FWaveSolver {

    private val LOGGER = LogManager.getLogger(FWaveSolver::class)

    fun solve(
        i0: Int, i1: Int,
        hSrc: FloatArray,
        huSrc: FloatArray,
        b: FloatArray,
        hDst: FloatArray,
        huDst: FloatArray,
        gravity: Float,
        scaling: Float,
        tmp4f: FloatArray
    ) {

        var h0 = hSrc[i0]
        var h1 = hSrc[i1]

        var b0 = b[i0]
        var b1 = b[i1]

        val wet0 = h0 > 0f
        val wet1 = h1 > 0f

        if (wet0 || wet1) {

            var hu0 = huSrc[i0]
            var hu1 = huSrc[i1]

            // apply dry-wet condition
            if (!wet0) {// left cell is dry
                h0 = h1
                b0 = b1
                hu0 = -hu1
            } else if (!wet1) {// right cell is dry
                h1 = h0
                b1 = b0
                hu1 = -hu0
            }

            solve(
                h0, h1,
                hu0, hu1,
                b0, b1,
                gravity,
                tmp4f
            )

            // apply changes left
            if (wet0) {
                hDst[i0] -= scaling * tmp4f[0]
                huDst[i0] -= scaling * tmp4f[1]
            }

            // apply changes right
            if (wet1) {
                hDst[i1] -= scaling * tmp4f[2]
                huDst[i1] -= scaling * tmp4f[3]
            }

        }
    }

    fun solve(
        h0: Float, h1: Float,
        hu0: Float, hu1: Float,
        b0: Float, b1: Float,
        gravity: Float,
        dst: FloatArray
    ) {
        // println("input: $h0 $h1 $hu0 $hu1 $b0 $b1 $gravity")
        val roeHeight = (h0 + h1) * 0.5f
        val sqrt0 = sqrt(h0)
        val sqrt1 = sqrt(h1)
        val u0 = if (h0 > 0f) hu0 / h0 else 0f
        val u1 = if (h1 > 0f) hu1 / h1 else 0f
        val roeVelocity = (u0 * sqrt0 + u1 * sqrt1) / (sqrt0 + sqrt1)
        val gravityTerm = sqrt(gravity * roeHeight)
        val lambda0 = roeVelocity - gravityTerm
        val lambda1 = roeVelocity + gravityTerm
        val invDeltaLambda = 0.5f / gravityTerm
        val bathymetryTermV2 = roeHeight * (b1 - b0)
        val df0 = hu1 - hu0
        val df1 = hu1 * u1 - hu0 * u0 + gravity * (0.5f * (h1 * h1 - h0 * h0) + bathymetryTermV2)
        val deltaH0 = +(df0 * lambda1 - df1) * invDeltaLambda
        val deltaH1 = -(df0 * lambda0 - df1) * invDeltaLambda
        val deltaHu0 = deltaH0 * lambda0
        val deltaHu1 = deltaH1 * lambda1
        // println("dh: $deltaH0 $deltaH1 by $df0 $df1")
        if (lambda0 < 0f) {// first wave to the left
            dst[0] = deltaH0
            dst[1] = deltaHu0
            dst[2] = 0f
            dst[3] = 0f
        } else {
            dst[0] = 0f
            dst[1] = 0f
            dst[2] = deltaH0
            dst[3] = deltaHu0
        }
        if (lambda1 < 0f) {// second wave to the right
            dst[0] += deltaH1
            dst[1] += deltaHu1
        } else {
            dst[2] += deltaH1
            dst[3] += deltaHu1
        }
        if (dst.any { it.isNaN() }) {
            dst.fill(0f)
        }
        // if (dst.any { it.isNaN() }) throw RuntimeException("NaN from $h0 $h1, $hu0 $hu1, $b0 $b1, $gravity")
    }


    fun solve(
        i0: Int, i1: Int,
        hSrc: FloatArray,
        huSrc: FloatArray,
        b: FloatArray,
        hDst: FloatArray,
        huDst: FloatArray,
        gravity: Float,
        scaling: Float,
        tmp4f: DoubleArray
    ) {

        var h0 = hSrc[i0]
        var h1 = hSrc[i1]

        var b0 = b[i0]
        var b1 = b[i1]

        val wet0 = h0 > 0f
        val wet1 = h1 > 0f

        if (wet0 || wet1) {

            var hu0 = huSrc[i0]
            var hu1 = huSrc[i1]

            // apply dry-wet condition
            if (!wet0) {// left cell is dry
                h0 = h1
                b0 = b1
                hu0 = -hu1
            } else if (!wet1) {// right cell is dry
                h1 = h0
                b1 = b0
                hu1 = -hu0
            }

            solve(
                h0.toDouble(), h1.toDouble(),
                hu0.toDouble(), hu1.toDouble(),
                b0.toDouble(), b1.toDouble(),
                gravity.toDouble(),
                tmp4f
            )

            // apply changes left
            if (wet0) {
                hDst[i0] = (hDst[i0] - scaling * tmp4f[0]).toFloat()
                huDst[i0] = (huDst[i0] - scaling * tmp4f[1]).toFloat()
            }

            // apply changes right
            if (wet1) {
                hDst[i1] = (hDst[i1] - scaling * tmp4f[2]).toFloat()
                huDst[i1] = (huDst[i1] - scaling * tmp4f[3]).toFloat()
            }

        }
    }

    fun solve(
        h0: Double, h1: Double,
        hu0: Double, hu1: Double,
        b0: Double, b1: Double,
        gravity: Double,
        dst: DoubleArray
    ) {
        // println("input: $h0 $h1 $hu0 $hu1 $b0 $b1 $gravity")
        val roeHeight = (h0 + h1) * 0.5
        val sqrt0 = sqrt(h0)
        val sqrt1 = sqrt(h1)
        val u0 = if (h0 > 0f) hu0 / h0 else 0.0
        val u1 = if (h1 > 0f) hu1 / h1 else 0.0
        val roeVelocity = (u0 * sqrt0 + u1 * sqrt1) / (sqrt0 + sqrt1)
        val gravityTerm = sqrt(gravity * roeHeight)
        val lambda0 = roeVelocity - gravityTerm
        val lambda1 = roeVelocity + gravityTerm
        val invDeltaLambda = 0.5 / gravityTerm
        val bathymetryTermV2 = roeHeight * (b1 - b0)
        val df0 = hu1 - hu0
        val df1 = hu1 * u1 - hu0 * u0 + gravity * (0.5 * (h1 * h1 - h0 * h0) + bathymetryTermV2)
        val deltaH0 = +(df0 * lambda1 - df1) * invDeltaLambda
        val deltaH1 = -(df0 * lambda0 - df1) * invDeltaLambda
        val deltaHu0 = deltaH0 * lambda0
        val deltaHu1 = deltaH1 * lambda1
        // println("dh: $deltaH0 $deltaH1 by $df0 $df1")
        if (lambda0 < 0.0) {// first wave to the left
            dst[0] = deltaH0
            dst[1] = deltaHu0
            dst[2] = 0.0
            dst[3] = 0.0
        } else {
            dst[0] = 0.0
            dst[1] = 0.0
            dst[2] = deltaH0
            dst[3] = deltaHu0
        }
        if (lambda1 < 0.0) {// second wave to the right
            dst[0] += deltaH1
            dst[1] += deltaHu1
        } else {
            dst[2] += deltaH1
            dst[3] += deltaHu1
        }
        if (dst.any { it.isNaN() }) {
            dst.fill(0.0)
        }
        // if (dst.any { it.isNaN() }) throw RuntimeException("NaN from $h0 $h1, $hu0 $hu1, $b0 $b1, $gravity")
    }


    @JvmStatic
    fun main(args: Array<String>) {

        testBorder()

        testOutflow()

        testNaN()

        testWithHandSelected()

        testSmall()

        testFromFile()

    }

    fun testBorder() {
        val w = 100
        val h = 50
        val sim = FluidSim()
        sim.width = w
        sim.height = h
        val setup = LinearDiscontinuity()
        setup.heightLeft = 0.2f
        setup.heightRight = 0.8f
        setup.borderHeight = 1.0f
        sim.setup = setup
        sim.ensureFieldSize()
        val height = (sim.engine as CPUEngine).fluidHeight
        ImageWriter.writeImageFloat(w + 2, h + 2, "border.png", false, height)
    }

    fun testOutflow() {
        val w = 100
        val h = 50
        val sim = FluidSim()
        sim.width = w
        sim.height = h
        val height = FloatArray((w + 2) * (h + 2))
        for (i in height.indices) height[i] = Math.random().toFloat()
        sim.setGhostOutflow(w, h, height)
        ImageWriter.writeImageFloat(w + 2, h + 2, "outflow.png", false, height)
    }

    fun testNaN() {
        val tmp = FloatArray(4)
        solve(230.46196f, 6.285231E-22f, -55848.68f, -1828.01f, -497.51163f, -127.29645f, 9.81f, tmp)
    }

    private fun testSmall() {
        val sim = FluidSim()
        sim.width = 100
        sim.height = 1
        val setup = LinearDiscontinuity()
        setup.heightLeft = 10f
        setup.heightRight = 8f
        setup.hasBorder = false
        sim.setup = setup
        assert(sim.ensureFieldSize())
        val step = 0.1f
        println((0 until 100).joinToString { sim.getFluidHeightAt(it, 0).toString() })
        sim.computeStep(step)
        println((0 until 100).joinToString { sim.getFluidHeightAt(it, 0).toString() })
        for (i in 0 until 49) {
            assert(sim.getFluidHeightAt(i, 0) == 10f)
            assert(sim.getMomentumXAt(i, 0) == 0f)
        }
        assert(sim.getFluidHeightAt(49, 0), 10 - step * 9.394671362f, 0.01f)
        assert(sim.getMomentumXAt(49, 0), step * 88.25985f, 0.01f)
        assert(sim.getFluidHeightAt(50, 0), 8 + step * 9.394671362f, 0.01f)
        assert(sim.getMomentumXAt(50, 0), step * 88.25985f, 0.01f)
        for (i in 51 until 100) {
            assert(sim.getFluidHeightAt(i, 0), 8f, 1e-5f)
            assert(sim.getMomentumXAt(i, 0), 0f, 1e-5f)
        }
    }

    fun assert(b: Boolean) {
        if (!b) throw RuntimeException()
    }

    fun assert(a: Float, b: Float, delta: Float) {
        if (abs(a - b) > delta) throw RuntimeException("$a != $b, ${abs(a - b)} > $delta")
    }

    private fun testWithHandSelected() {
        // tests
        val dst = FloatArray(4)
        val g = 9.81f
        solve(10f, 10f, 4f, 4f, 0f, 0f, g, dst)
        assert(dst[0] == 0f)
        assert(dst[1] == 0f)
        assert(dst[2] == 0f)
        assert(dst[3] == 0f)
        solve(10f, 0f, 10f, 0f, 0f, 0f, g, dst)
        println(dst.joinToString())
        assert(abs(+30.017855 - dst[0]) < 0.01)
        assert(abs(-180.21432 - dst[1]) < 0.01)
        assert(abs(-40.017855 - dst[2]) < 0.01)
        assert(abs(-320.28574 - dst[3]) < 0.01)
        solve(10f, 10f, 10f, -10f, 0f, 0f, g, dst)
        println(dst.joinToString())
        assert(abs(-10.000000f - dst[0]) < 0.01)
        assert(abs(+99.045684f - dst[1]) < 0.01)
        assert(abs(-10.000000f - dst[2]) < 0.01)
        assert(abs(-99.045684f - dst[3]) < 0.01)
        solve(10f, 50f, 0f, 0f, 50f, 10f, g, dst)
        println(dst.joinToString())
        assert(abs(dst[0]) < 0.001)
        assert(abs(dst[1]) < 0.001)
        assert(abs(dst[2]) < 0.001)
        assert(abs(dst[3]) < 0.001)
    }

    private fun testFromFile() {

        val sim = FluidSim()
        sim.width = 32
        sim.height = 1
        sim.gravity = 9.80665f

        val halfIndex = sim.width / 2

        var hl = 0f
        var hr = 0f
        var hul = 0f
        var hur = 0f

        val setup = object : FluidSimSetup() {
            override fun getHeight(x: Int, y: Int, w: Int, h: Int): Float {
                return if (x * 2 <= w) hl else hr
            }

            override fun getMomentumX(x: Int, y: Int, w: Int, h: Int): Float {
                return if (x * 2 <= w) hul else hur
            }
        }

        val lines = getReference("E:/Documents/Uni/Master/WS2122/tsunami/data/middle_states.csv")
            .inputStream().bufferedReader()

        var numPassed = 0
        var numTests = 0
        while (numTests < 500e3) {

            val line = lines.readLine() ?: break
            if (line.isEmpty() || line[0] == 'h' || line[0] == '#') continue

            numTests++

            val parts = line.split(',').map { it.toFloat() }
            hl = parts[0]
            hr = parts[1]
            hul = parts[2]
            hur = parts[3]

            sim.initWithSetup(setup)

            val hStar = parts[4]
            val targetPrecision = 0.05f * abs(hStar)

            val maxSteps = sim.width
            for (i in 0 until maxSteps) {

                try {
                    sim.step(1e9f, 1)
                } catch (e: Exception) {
                    // e.printStackTrace()
                    LOGGER.warn("failed line[$numTests] $line with $e")
                    break
                }

                val hComputed = sim.getFluidHeightAt(halfIndex, 0)

                val passed = abs(hStar - hComputed) < targetPrecision
                if (passed) {
                    // println("passed $index $line :)")
                    numPassed++
                    break
                }
                if (i == maxSteps - 1) LOGGER.warn("did not pass, got $hComputed instead of $hStar from $line, line $numTests")
            }
        }

        LOGGER.info("$numPassed/$numTests passed")

    }

}