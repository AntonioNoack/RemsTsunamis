package me.anno.tsunamis.perf

import me.anno.Engine
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.files.InvalidRef
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

Theoretical CPU performance: 6 cores * 3.4GHz base clock * 4 (avx256) = 81.6 GFlops (FMA currently ignored, because that can be only used sometimes)
DDR4 3200: 25.6 GB/s (but it should be dual channel, so x2?)
Theoretical GPU performance: 6.2 TFlops, 256 GB/s


On Tesla P100, 16GB
9.3 TFlops, 732 or 549GB/s bandwidth

[09:18:30,INFO:ComputeShader] Max compute group count: 2147483647 x 65535 x 65535
[09:18:30,INFO:ComputeShader] Max units per group: 1536
[09:17:59,INFO:Performance] CPU, 3 iterations
[09:18:13,INFO:Performance] X half-step is 1.00x faster than Y half-step
[09:18:13,INFO:Performance] 11.001 s, 3.11 GFlop/s, 1.13 GB/s
[09:18:16,INFO:Performance] GPU_GRAPHICS, 1717 iterations
[09:18:27,INFO:Performance] X half-step is 1.00x faster than Y half-step
[09:18:27,INFO:Performance] 8.872 s, 2208.35 GFlop/s (709.69x), 803.04 GB/s
[09:18:30,INFO:Performance] GPU_COMPUTE, 952 iterations
[09:18:42,INFO:Performance] X half-step is 1.13x faster than Y half-step
[09:18:42,INFO:Performance] 9.977 s, 1088.79 GFlop/s (349.90x, 0.49x), 395.92 GB/s
[09:18:45,INFO:Performance] GPU_2PASSES, 429 iterations
[09:18:56,INFO:Performance] X half-step is 1.08x faster than Y half-step
[09:18:56,INFO:Performance] 9.193 s, 532.50 GFlop/s (171.13x, 0.24x, 0.49x), 193.63 GB/s
[09:18:59,INFO:Performance] GPU_SHARED_MEMORY, 1010 iterations
[09:19:11,INFO:Performance] X half-step is 1.30x faster than Y half-step
[09:19:11,INFO:Performance] 9.996 s, 1152.94 GFlop/s (370.52x, 0.52x, 1.06x, 2.17x), 419.25 GB/s
[09:19:14,INFO:Performance] GPU_COMPUTE_YX, 62 iterations
[09:19:26,INFO:Performance] X half-step is 1.08x faster than Y half-step
[09:19:26,INFO:Performance] 10.095 s, 70.08 GFlop/s (22.52x, 0.03x, 0.06x, 0.13x, 0.06x), 25.48 GB/s
[09:19:29,INFO:Performance] GPU_COMPUTE_FP16B32, 1089 iterations
[09:19:41,INFO:Performance] X half-step is 0.99x faster than Y half-step
[09:19:41,INFO:Performance] 9.970 s, 1246.31 GFlop/s (400.52x, 0.56x, 1.14x, 2.34x, 1.08x, 17.78x), 198.28 GB/s
[09:19:44,INFO:Performance] GPU_COMPUTE_FP16B16, 1112 iterations
[09:19:56,INFO:Performance] X half-step is 0.97x faster than Y half-step
[09:19:56,INFO:Performance] 9.979 s, 1271.56 GFlop/s (408.64x, 0.58x, 1.17x, 2.39x, 1.10x, 18.14x, 1.02x), 144.50 GB/s


RX 580 again, more gpu solver variants

[12:06:19,INFO:Performance] CPU, 16 iterations
[12:06:29,INFO:Performance] 9.884 s, 18.46 GFlop/s, 6.71 GB/s
[12:06:31,INFO:Performance] GPU_GRAPHICS, 403 iterations
[12:06:41,INFO:Performance] 9.902 s, 464.17 GFlop/s (25.14x), 168.79 GB/s
[12:06:43,INFO:ComputeShader] Max compute group count: 65535 x 65535 x 65535
[12:06:43,INFO:ComputeShader] Max units per group: 1024
[12:06:44,INFO:Performance] GPU_COMPUTE, 429 iterations
[12:06:54,INFO:Performance] 9.847 s, 496.85 GFlop/s (26.91x, 1.07x), 180.67 GB/s
[12:06:57,INFO:Performance] GPU_2PASSES, 183 iterations
[12:07:07,INFO:Performance] 9.995 s, 208.80 GFlop/s (11.31x, 0.45x, 0.42x), 75.93 GB/s
[12:07:10,INFO:Performance] GPU_SHARED_MEMORY, 416 iterations
[12:07:20,INFO:Performance] 9.806 s, 483.85 GFlop/s (26.21x, 1.04x, 0.97x, 2.32x), 175.94 GB/s
[19:12:27,INFO:Performance] GPU_COMPUTE_YX, 300 iterations
[19:12:39,INFO:Performance] X half-step is 0.99x faster than Y half-step
[19:12:39,INFO:Performance] 9.937 s, 344.31 GFlop/s, 125.20 GB/s

fp16 maths with fp32 bathymetry:
[08:48:00,INFO:Performance] GPU_COMPUTE_FP16B32, 835 iterations
[08:48:12,INFO:Performance] X half-step is 1.12x faster than Y half-step
[08:48:12,INFO:Performance] 9.754 s, 976.27 GFlop/s, 155.32 GB/s

fp16 maths with fp16 bathymetry:
[08:44:19,INFO:Performance] GPU_COMPUTE_FP16B16, 881 iterations
[08:44:31,INFO:Performance] X half-step is 1.13x faster than Y half-step
[08:44:31,INFO:Performance] 9.654 s, 1040.73 GFlop/s, 118.26 GB/s



s_hadoop, 2x Intel Xeon Gold 6140 18 Core 2.3 GHz

[14:44:50,INFO:Performance] CPU, 36 iterations
[14:45:00,INFO:Performance] X half-step is 0.97x faster than Y half-step
[14:45:00,INFO:Performance] 9.088 s, 45.20 GFlop/s, 16.44 GB/s

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

    val compareXY = config.getOrDefault("compareXY", true)

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

    logger.info("Field Size $width x $height")

    val types = ArrayList<EngineType>()
    if (testCPU) types += EngineType.CPU
    if (testGPU) {
        types += EngineType.GPU_GRAPHICS
        types += EngineType.GPU_COMPUTE
        types += EngineType.GPU_2PASSES
        types += EngineType.GPU_SHARED_MEMORY
        types += EngineType.GPU_COMPUTE_YX
        types += EngineType.GPU_COMPUTE_FP16B32
        types += EngineType.GPU_COMPUTE_FP16B16
    }

    for (type in types) {

        waitUntil(true) { setup.isReady() }

        val engine = type.create(width, height)
        engine.init(null, setup, gravity)

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

        // run performance measurements
        val t2 = System.nanoTime()
        for (i in 0 until numIterations) {
            engine.step(gravity, scaling)
        }
        engine.synchronize()
        val t3 = System.nanoTime()

        // compare step on x and y axis
        if (compareXY) {
            val numIterations2 = max(1, numIterations / 5)
            val t4 = System.nanoTime()
            for (i in 0 until numIterations2) {
                engine.halfStep(gravity, scaling, true)
            }
            engine.synchronize()
            val t5 = System.nanoTime()
            for (i in 0 until numIterations2) {
                engine.halfStep(gravity, scaling, false)
            }
            engine.synchronize()
            val t6 = System.nanoTime()
            val dtX = (t5 - t4) * 1e-9
            val dtY = (t6 - t5) * 1e-9
            logger.info("X half-step is ${(dtY / dtX).f2()}x faster than Y half-step")
        }

        // compute results
        val duration = (t3 - t2) * 1e-9
        val durPerIteration = duration / numIterations
        val flops = 88 * 2.0 * numIterations * (width * height).toDouble()
        val gigaFlops = flops / duration * 1e-9

        val bandwidth = when (type) {
            // (load 3*fp16 + store 2*fp16), 2 half-steps, for all cells
            EngineType.GPU_COMPUTE_FP16B16 -> (3 + 2) * 2 * numIterations * 2 * (width * height).toDouble() / duration / 1e9
            // (load 2*fp16 + fp32 + store 2*fp16), 2 half-steps, for all cells
            EngineType.GPU_COMPUTE_FP16B32 -> ((3 + 2) * 2 + 4) * numIterations * 2 * (width * height).toDouble() / duration / 1e9
            // (load + store), (4 floats each (h,hu,hv,b)), 2 half-steps, for all cells
            else -> 2 * 4 * 4 * numIterations * 2 * (width * height).toDouble() / duration / 1e9
        }

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

    if (testGPU && config.getOrDefault("egl", false)) {
        HeadlessOpenGLContext.destroyContext()
    }

    // stop all remaining threads gracefully
    Engine.requestShutdown()

}