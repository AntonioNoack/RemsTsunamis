package me.anno.tsunamis

import me.anno.Engine
import me.anno.gpu.GFX
import me.anno.gpu.GFXState.useFrame
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.Variable
import me.anno.tsunamis.egl.EGLContext
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.EngineType
import me.anno.tsunamis.engine.TsunamiEngine.Companion.getMaxValue
import me.anno.tsunamis.perf.SetupLoader
import me.anno.tsunamis.perf.SetupLoader.getOrDefault
import me.anno.utils.OS.desktop
import me.anno.utils.Sleep.waitUntil
import me.anno.utils.types.Vectors.print
import me.anno.video.VideoCreator.Companion.renderVideo
import org.apache.logging.log4j.LogManager
import org.joml.Vector4f
import kotlin.math.min
import kotlin.math.roundToInt

object VideoRenderer {

    // loggers are used to identify where log messages are coming from,
    // and to filter, if needed
    private val LOGGER = LogManager.getLogger(VideoRenderer::class)

    private val showWavesShader = ShaderLib.createShader(
        "copy", ShaderLib.simplestVertexShader, listOf(Variable(GLSLType.V2F, "uv")), "" +
                "uniform sampler2D tex;\n" +
                "uniform vec3 scale;\n" +
                "void main(){\n" +
                "   vec4 data = texture(tex, uv);\n" +
                "   float surface = scale.x * (data.x + data.w);\n" +
                "   vec2 momentum = clamp(data.yz * scale.yz, vec2(-1.0), vec2(1.0));\n" +
                "   gl_FragColor = vec4(vec3(surface + 0.5)\n" +
                "        + vec3( 1.0, 0.0, -1.0) * momentum.x\n" +
                "        + vec3(-0.5, 1.0, -0.5) * momentum.y, 1.0);\n" +
                "}", listOf("tex")
    )

    // when a main function is inside an object or class,
    // @JvmStatic and Array<String> need to be added to the function
    // for it to be registered as an executable main function within Intellij Idea
    @JvmStatic
    fun main(args: Array<String>) {

        val fullSetup = SetupLoader.load(args)
        val setup = fullSetup.setup
        val config = fullSetup.config

        val cflFactor = fullSetup.cflFactor
        val gravity = fullSetup.gravity

        val minFluidHeight = fullSetup.minFluidHeight

        val w = fullSetup.width
        val h = fullSetup.height
        val numFrames = config.getOrDefault("numFrames", 500)
        val numStepsPerFrame = config.getOrDefault("stepsPerFrame", 10)

        val outputScale = config.getOrDefault("outputScale", min(1f, 1024f / w))

        val outputWidth = (w * outputScale).roundToInt()
            .and(1.inv()) // round size, and make even (because some codecs only support even side lengths)
        val outputHeight = (h * outputScale).roundToInt()
            .and(1.inv())

        val engineType = when (config.getOrDefault("engineType", "").lowercase()) {
            "compute" -> EngineType.GPU_COMPUTE
            "fp16-b16" -> EngineType.GPU_COMPUTE_FP16B16
            "fp16-b32" -> EngineType.GPU_COMPUTE_FP16B32
            "shared-memory" -> EngineType.GPU_SHARED_MEMORY
            "two-passes" -> EngineType.GPU_2PASSES
            "yx" -> EngineType.GPU_COMPUTE_YX
            else -> EngineType.GPU_GRAPHICS
        }

        LOGGER.info("Output Size: $outputWidth x $outputHeight, engine type: $engineType")

        if (config.getOrDefault("egl", false)) {
            val useDefaultDisplay = config.getOrDefault("eglUseDefaultDisplay", false)
            // this size parameter shouldn't matter
            // it's the size of the default framebuffer
            EGLContext.createContext(512, 512, useDefaultDisplay)
        } else {
            HiddenOpenGLContext.createOpenGL()
        }

        ShaderLib.init()

        val engine = EngineType.create(engineType, w, h) as CPUEngine

        val maxMomentum = config.getOrDefault("maxMomentum", 100f)

        waitUntil(true) { setup.isReady() }
        engine.init(null, setup, gravity, minFluidHeight)

        LOGGER.info("Preferred size: ${setup.getPreferredNumCellsX()} x ${setup.getPreferredNumCellsY()}")

        val scaling = cflFactor / engine.computeMaxVelocity(gravity, minFluidHeight)

        val maxFluidHeight = getMaxValue(w, h, 1, engine.fluidHeight, engine.bathymetry)

        val maxValues = Vector4f()
        val srcFB = Framebuffer("src", outputWidth, outputHeight, 1, 1, true, DepthBufferType.NONE)
        renderVideo(outputWidth, outputHeight, 30.0, desktop.getChild("height.mp4"), numFrames, srcFB) { _, callback ->
            for (i in 0 until numStepsPerFrame) {
                engine.step(gravity, scaling, minFluidHeight)
            }
            // maxValues.max(Reduction.reduce(engine.src, Reduction.MAX_RA))
            useFrame(srcFB) {
                val shader = showWavesShader.value
                shader.use()
                shader.v3f("scale", 1f / maxFluidHeight, 1f / maxMomentum, 1f / maxMomentum)
                engine.requestFluidTexture(w, h).bind(0)
                GFX.flat01.draw(shader)
            }
            callback()
        }

        LOGGER.info("Maximum values: ${maxValues.print()}")

        Engine.requestShutdown()

    }

}