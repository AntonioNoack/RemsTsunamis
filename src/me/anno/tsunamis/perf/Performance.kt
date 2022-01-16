package me.anno.tsunamis.perf

import me.anno.Engine
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.EngineType
import me.anno.tsunamis.setups.NetCDFSetup
import me.anno.utils.Sleep.waitUntil
import me.anno.utils.types.Floats.f3
import org.apache.logging.log4j.LogManager
import kotlin.math.max
import kotlin.math.roundToInt

/**

 Benchmark results from 16.01.2022

[17:50:59,INFO:Performance] Running performance test for CPU, 976 iterations
[17:51:08,INFO:Performance] Used 8.485 s, 0.002024388378703037 GFlop/s, 0.0011042118429289293 GB/s
[17:51:08,INFO:Performance] Running performance test for GPU_GRAPHICS, 44587 iterations
[17:51:11,INFO:Performance] Used 2.906 s, 0.27003160314317093 GFlop/s, 0.19638662046776065 GB/s (133x faster)
[17:51:11,INFO:Performance] Running performance test for GPU_COMPUTE, 110534 iterations
[17:51:12,INFO:Performance] Used 0.707 s, 2.751773009517629 GFlop/s, 2.0012894614673664 GB/s (1359x/10.1x faster)

Theoretical CPU performance: 6 cores * 3.4GHz base clock = 20.4 GFlops (yes, FMA ignored, because that only can be used sometimes)
Theoretical GPU performance: 6.2 TFlops, 256 GB/s

 * */
fun main() {
    val logger = LogManager.getLogger("Performance")
    HiddenOpenGLContext.createOpenGL()
    val warmup = 10
    val testDuration = 10.0
    val folder = getReference("E:/Documents/Uni/Master/WS2122")
    for (type in EngineType.values()) {
        val sim = FluidSim()
        sim.engineType = type
        val setup = NetCDFSetup()
        setup.bathymetryFile = getReference(folder, "tohoku_gebco08_ucsb3_250m_bath.nc")
        setup.displacementFile = getReference(folder, "tohoku_gebco08_ucsb3_250m_displ.nc")
        waitUntil(true) { setup.isReady() }
        sim.initWithSetup(setup)
        // step a few iterations
        val dt = sim.computeMaxTimeStep()
        sim.engine!!.synchronize()
        // compile the shaders & such
        sim.step(dt)
        sim.engine!!.synchronize()
        val t0 = System.nanoTime()
        // guess the performance
        for (i in 0 until warmup) {
            sim.step(dt)
        }
        sim.engine!!.synchronize()
        val t1 = System.nanoTime()
        val numIterations = max(warmup, (warmup * testDuration / ((t1 - t0) * 1e-9)).roundToInt())
        logger.info("Running performance test for $type, $numIterations iterations")
        val t2 = System.nanoTime()
        // run performance measurements
        for (i in 0 until numIterations) {
            sim.step(dt)
        }
        sim.engine!!.synchronize()
        val t3 = System.nanoTime()
        val duration = (t3 - t2) * 1e-9
        val flops = 88 * 2.0 * numIterations * (sim.width * sim.height).toDouble()
        val gigaFlops = flops / duration * 1e-9
        val bandwidth = when (type) {
            // load (left + right, store result), (4 floats each (h,hu,hv,b)), 2 half-steps, for all cells
            EngineType.CPU -> 3 * 4 * 4 * numIterations * 2 * (sim.width * sim.height).toDouble() / duration / 1e9
            // load (left + center + right, store result), (4 floats each (h,hu,hv,b)), 2 half-steps, for all cells
            else -> 4 * 4 * 4 * numIterations * 2 * (sim.width * sim.height).toDouble() / duration / 1e9
        }
        logger.info("Used ${duration.f3()} s, $gigaFlops GFlop/s, $bandwidth GB/s")
        sim.destroy()
    }
    Engine.requestShutdown()
}