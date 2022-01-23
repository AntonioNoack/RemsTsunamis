package me.anno.tsunamis.perf

import me.anno.Engine
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.files.InvalidRef
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.aracluster.HeadlessOpenGLContext
import me.anno.tsunamis.engine.EngineType
import me.anno.tsunamis.perf.SetupLoader.getOrDefault
import me.anno.utils.Sleep.waitUntil
import me.anno.utils.types.Floats.f2
import me.anno.utils.types.Floats.f3
import org.apache.logging.log4j.LogManager
import kotlin.math.max
import kotlin.math.roundToInt

/**

Benchmark results from 16.01.2022, 10x10

[17:50:59,INFO:Performance] Running performance test for CPU, 976 iterations
[17:51:08,INFO:Performance] Used 8.485 s, 0.002024388378703037 GFlop/s, 0.0011042118429289293 GB/s
[17:51:08,INFO:Performance] Running performance test for GPU_GRAPHICS, 44587 iterations
[17:51:11,INFO:Performance] Used 2.906 s, 0.27003160314317093 GFlop/s, 0.19638662046776065 GB/s (133x faster)
[17:51:11,INFO:Performance] Running performance test for GPU_COMPUTE, 110534 iterations
[17:51:12,INFO:Performance] Used 0.707 s, 2.751773009517629 GFlop/s, 2.0012894614673664 GB/s (1359x/10.1x faster)


Benchmark results from 16.01.2022, 10800 x 6000

[19:02:26,INFO:Performance] CPU, 8 iterations
[19:02:36,INFO:Performance] 9.377 s, 9.729506342701125 GFlop/s, 5.307003459655159 GB/s | C++ 0.31s/iteration, so 3.8x faster than Kotlin
[19:02:39,INFO:Performance] GPU_GRAPHICS, 409 iterations
[19:02:49,INFO:Performance] 9.957 s, 468.471892207911 GFlop/s (48.15x), 340.70683069666256 GB/s | 12.7x faster than C++
[19:02:51,INFO:Performance] GPU_COMPUTE, 412 iterations
[19:03:01,INFO:Performance] 9.887 s, 475.2597631461869 GFlop/s (48.85x, 1.01x), 345.64346410631777 GB/s | 12.9x faster than C++


Benchmark results from 23.01.2022, 10800 x 6000

[07:35:35,INFO:Performance] CPU, 16 iterations
[07:35:45,INFO:Performance] 9.972 s, 18.298763743805395 GFlop/s, 9.981143860257488 GB/s
[07:35:48,INFO:Performance] GPU_GRAPHICS, 411 iterations
[07:35:58,INFO:Performance] 9.674 s, 484.5578165111331 GFlop/s (26.48x), 176.20284236768475 GB/s
[07:36:00,INFO:ComputeShader] Max compute group count: 65535 x 65535 x 65535
[07:36:00,INFO:ComputeShader] Max units per group: 1024
[07:36:00,INFO:Performance] GPU_COMPUTE, 418 iterations
[07:36:10,INFO:Performance] 9.900 s, 481.52831500264944 GFlop/s (26.31x, 0.99x), 175.10120545550888 GB/s


After disabling auto-generating mipmaps (the same):

[07:50:51,INFO:Performance] CPU, 17 iterations
[07:51:01,INFO:Performance] 10.068 s, 19.25666509215164 GFlop/s, 7.002423669873322 GB/s
[07:51:03,INFO:Performance] GPU_GRAPHICS, 420 iterations
[07:51:13,INFO:Performance] 9.681 s, 494.7960490037346 GFlop/s (25.69x), 179.925836001358 GB/s
[07:51:15,INFO:ComputeShader] Max compute group count: 65535 x 65535 x 65535
[07:51:15,INFO:ComputeShader] Max units per group: 1024
[07:51:15,INFO:Performance] GPU_COMPUTE, 419 iterations
[07:51:25,INFO:Performance] 9.875 s, 483.8971563488645 GFlop/s (25.13x, 0.98x), 175.962602308678 GB/s


On Tesla P100, 16GB

[10:04:08,INFO:Performance] CPU, 3 iterations
[10:04:19,INFO:Performance] 11.098 s, 3.08 GFlop/s, 1.12 GB/s
[10:04:22,INFO:Performance] GPU_GRAPHICS, 758 iterations
[10:04:26,INFO:Performance] 3.968 s, 2179.74 GFlop/s (706.68x), 792.63 GB/s
[10:04:30,INFO:ComputeShader] Max compute group count: 2147483647 x 65535 x 65535
[10:04:30,INFO:ComputeShader] Max units per group: 1536
[10:04:30,INFO:Performance] GPU_COMPUTE, 1096 iterations
[10:04:40,INFO:Performance] 9.963 s, 1255.30 GFlop/s (406.97x, 0.58x), 456.47 GB/s

Theoretical CPU performance: 6 cores * 3.4GHz base clock * 4 (avx256) = 81.6 GFlops (FMA currently ignored, because that can be only used sometimes)
DDR4 3200: 25.6 GB/s (but it should be dual channel, so x2?)
Theoretical GPU performance: 6.2 TFlops, 256 GB/s

 * */
fun main(args: Array<String>) {

    val logger = LogManager.getLogger("Performance")

    val configFile = if (args.isNotEmpty()) getReference(args[0]) else InvalidRef
    val loaded = SetupLoader.load(configFile)
    val setup = loaded.setup
    val width = loaded.width
    val height = loaded.height
    val config = loaded.config

    val testCPU = config.getOrDefault("testCPU", true)
    val testGPU = config.getOrDefault("testGPU", true)

    if (!testCPU && !testGPU) {
        logger.warn("Neither CPU not GPU were tested")
        return
    }

    if (testGPU) {
        if (config.getOrDefault("egl", false)) {
            val useDefaultDisplay = config.getOrDefault("eglUseDefaultDisplay", false)
            // this size parameter shouldn't matter
            // it's the size of the default framebuffer
            HeadlessOpenGLContext.createContext(512, 512, useDefaultDisplay)
        } else {
            HiddenOpenGLContext.createOpenGL()
        }
    }

    val warmupIterations = loaded.config.getOrDefault("warmupIterations", 2)
    val testDurationSeconds = loaded.config.getOrDefault("testDurationSeconds", 10.0)
    val cflFactor = loaded.cflFactor
    val gravity = loaded.gravity

    val speeds = ArrayList<Double>()

    waitUntil(true) { setup.isReady() }

    logger.info("Field Size $width x $height")

    val types = ArrayList<EngineType>()
    if (testCPU) types += EngineType.CPU
    if (testGPU) {
        types += EngineType.GPU_GRAPHICS
        types += EngineType.GPU_COMPUTE
        types += EngineType.GPU_2PASSES
        types += EngineType.GPU_SHARED_MEMORY
    }

    for (type in types) {

        val engine = type.create(width, height)

        waitUntil(true) { setup.isReady() }
        engine.init(FluidSim(), setup, gravity)

        // step a few iterations
        val scaling = cflFactor / engine.computeMaxVelocity(gravity)
        engine.synchronize()

        // compile the shaders & such
        engine.step(gravity, scaling)

        // guess the performance
        engine.synchronize()
        val t0 = System.nanoTime()
        for (i in 0 until warmupIterations) {
            engine.step(gravity, scaling)
        }
        engine.synchronize()
        val t1 = System.nanoTime()

        // compute the number of iterations, the solver will be capable of solving within about testDuration
        val numIterations =
            max(warmupIterations, (warmupIterations * testDurationSeconds / ((t1 - t0) * 1e-9)).roundToInt())
        logger.info("$type, $numIterations iterations")
        val t2 = System.nanoTime()

        // run performance measurements
        for (i in 0 until numIterations) {
            engine.step(gravity, scaling)
        }
        engine.synchronize()
        val t3 = System.nanoTime()

        // compute results
        val duration = (t3 - t2) * 1e-9
        val durPerIteration = duration / numIterations
        val flops = 88 * 2.0 * numIterations * (width * height).toDouble()
        val gigaFlops = flops / duration * 1e-9

        // (load + store), (4 floats each (h,hu,hv,b)), 2 half-steps, for all cells
        val bandwidth = 2 * 4 * 4 * numIterations * 2 * (width * height).toDouble() / duration / 1e9

        // print results
        val speedups = if (speeds.isNotEmpty())
            speeds.joinToString(", ", " (", ")") { "${(it / durPerIteration).f2()}x" }
        else ""
        logger.info(
            "" +
                    "${duration.f3()} s, " +
                    "${gigaFlops.f2()} GFlop/s$speedups, " +
                    "${bandwidth.f2()} GB/s"
        )

        engine.destroy()
        speeds.add(durPerIteration)
        System.gc()

    }

    // stop all remaining threads gracefully
    Engine.requestShutdown()

}