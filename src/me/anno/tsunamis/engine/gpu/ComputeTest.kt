package me.anno.tsunamis.engine.gpu

import me.anno.Engine
import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.useFrame
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.Variable
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.TsunamiEngine.Companion.getMaxValue
import me.anno.tsunamis.setups.CircularDiscontinuity
import me.anno.tsunamis.setups.LinearDiscontinuity
import me.anno.tsunamis.setups.NetCDFSetup
import me.anno.utils.OS.desktop
import me.anno.utils.Sleep.waitUntil
import me.anno.utils.types.Vectors.print
import me.anno.video.VideoCreator.Companion.renderVideo
import org.apache.logging.log4j.LogManager
import org.joml.Vector4f

object ComputeTest {

    // loggers are used to identify where log messages are coming from,
    // and to filter, if needed
    private val LOGGER = LogManager.getLogger(ComputeTest::class)

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

        // todo or we could read those yaml config files :)

        // 10800 x 6000
        // test the computation
        val w = 1024
        val h = 1024 / 2 // w * 60 / 108
        val numFrames = 500
        val numStepsPerFrame = 10

        val setupType = 2

        val w2 = 1024
        val h2 = w2 * h / w

        val cflFactor = 0.45f

        val gravity = 9.81f

        // HeadlessContext.createContext(w, h, false)
        HiddenOpenGLContext.createOpenGL(w, h)
        ShaderLib.init()

        val engine = ComputeEngine(w, h)
        var maxMomentum = 0f

        val setup = when (setupType) {
            0 -> {
                val setup = LinearDiscontinuity()
                setup.heightLeft = 0.5f
                setup.heightRight = 1.0f
                setup.hasBorder = true
                setup.borderHeight = 10f
                setup
            }
            1 -> {
                val setup = CircularDiscontinuity()
                setup.heightInner = 0.5f
                setup.heightOuter = 1.0f
                setup.hasBorder = true
                setup.borderHeight = 10f
                setup
            }
            2 -> {
                val setup = NetCDFSetup()
                val folder = getReference("E:/Documents/Uni/Master/WS2122")
                setup.bathymetryFile = folder.getChild("tohoku_gebco08_ucsb3_250m_bath.nc")
                setup.displacementFile = folder.getChild("tohoku_gebco08_ucsb3_250m_displ.nc")
                setup.hasBorder = true
                setup.borderHeight = 10f
                maxMomentum = 1314f // computed using Reduction
                setup
            }
            else -> throw RuntimeException("Simulation needs setup")
        }

        waitUntil(true) { setup.isReady() }
        engine.init(null, setup, gravity)

        LOGGER.info("Preferred size: ${setup.getPreferredNumCellsX()} x ${setup.getPreferredNumCellsY()}")

        val scaling = cflFactor / engine.computeMaxVelocity(gravity)

        val maxFluidHeight = getMaxValue(w, h, 1, engine.fluidHeight, engine.bathymetry)

        val maxValues = Vector4f()
        val srcFB = Framebuffer("src", w2, h2, 1, 1, true, DepthBufferType.NONE)
        renderVideo(w2, h2, 30.0, desktop.getChild("height.mp4"), numFrames, srcFB) { callback ->
            for (i in 0 until numStepsPerFrame) {
                engine.step(gravity, scaling)
            }
            maxValues.max(Reduction.reduce(engine.src, Reduction.MAX_RA))
            useFrame(srcFB) {
                val shader = showWavesShader.value
                shader.use()
                shader.v3f("scale", 1f / maxFluidHeight, 1f / maxMomentum, 1f / maxMomentum)
                engine.src.bind(0)
                GFX.flat01.draw(shader)
            }
            callback()
        }

        LOGGER.info("Maximum values: ${maxValues.print()}")

        Engine.requestShutdown()

    }

}