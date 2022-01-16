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
import me.anno.gpu.texture.Texture2D
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.ComputeEngine.Companion.step
import me.anno.tsunamis.engine.gpu.GLSLSolver.createTextureData
import me.anno.tsunamis.setups.CircularDiscontinuity
import me.anno.tsunamis.setups.LinearDiscontinuity
import me.anno.tsunamis.setups.NetCDFSetup
import me.anno.utils.OS.desktop
import me.anno.video.VideoCreator.Companion.renderVideo
import org.apache.logging.log4j.LogManager

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
                "   vec2 momentum = clamp(data.yz * scale.yz, vec2(0.0), vec2(1.0));\n" +
                "   gl_FragColor = vec4(vec3(surface)\n" +
                "        + vec3( 1.0, 0.0, -1.0) * momentum.x\n" +
                "        + vec3(-0.5, 1.0, -0.5) * momentum.y, 1.0);\n" +
                "}", listOf("tex")
    )

    // when a main function is inside an object or class,
    // @JvmStatic and Array<String> need to be added to the function
    // for it to be registered as an executable main function within Intellij Idea
    @JvmStatic
    fun main(args: Array<String>) {

        // todo apache cli

        // todo or we could read those yaml config files :)

        // 10800 x 6000
        // test the computation
        val w = 1024
        val h = 1024 / 2 // w * 60 / 108
        val numFrames = 500
        val numStepsPerFrame = 50

        val setupType = 2

        val w2 = 1024
        val h2 = w2 * h / w

        // HeadlessContext.createContext(w, h, false)
        HiddenOpenGLContext.createOpenGL(w, h)
        ShaderLib.init()

        val fluidSim = FluidSim()
        fluidSim.computeOnly = true
        fluidSim.width = w
        fluidSim.height = h
        fluidSim.cellSizeMeters = 100f

        when (setupType) {
            0 -> {
                val setup = LinearDiscontinuity()
                setup.heightLeft = 0.5f
                setup.heightRight = 1.0f
                setup.hasBorder = true
                setup.borderHeight = 10f
                fluidSim.setup = setup
            }
            1 -> {
                val setup = CircularDiscontinuity()
                setup.heightInner = 0.5f
                setup.heightOuter = 1.0f
                setup.hasBorder = true
                setup.borderHeight = 10f
                fluidSim.setup = setup
            }
            2 -> {
                val setup = NetCDFSetup()
                val folder = getReference("E:/Documents/Uni/Master/WS2122")
                setup.bathymetryFile = folder.getChild("tohoku_gebco08_ucsb3_250m_bath.nc")
                setup.displacementFile = folder.getChild("tohoku_gebco08_ucsb3_250m_displ.nc")
                setup.hasBorder = true
                setup.borderHeight = 10f
                val originalWidth = 10800
                fluidSim.cellSizeMeters = 250f * originalWidth / w
                fluidSim.setup = setup
            }
        }

        val setup = fluidSim.setup ?: throw RuntimeException("Simulation needs setup")
        while (!fluidSim.ensureFieldSize()) {
            LOGGER.info("Waiting for setup to load")
            Thread.sleep(500)
        }

        LOGGER.info("Preferred size: ${setup.getPreferredNumCellsX()} x ${setup.getPreferredNumCellsY()}")

        val src = Texture2D("src", w, h, 1)
        val tmp = Texture2D("tmp", w, h, 1)

        val engine = fluidSim.engine as CPUEngine
        fluidSim.setGhostOutflow(w, h, engine.fluidHeight)
        fluidSim.setGhostOutflow(w, h, engine.fluidMomentumX)
        fluidSim.setGhostOutflow(w, h, engine.fluidMomentumY)
        fluidSim.setGhostOutflow(w, h, engine.bathymetry)

        val data = createTextureData(w, h, engine)
        src.createRGBA(data, false)
        tmp.createRGBA(data, false)

        val maxTimeStep = fluidSim.computeMaxTimeStep()
        val timeScale = maxTimeStep / fluidSim.cellSizeMeters

        // call barrier for memory read
        // glMemoryBarrier(GL_PIXELBUFFER_BARRIER_BIT)

        /*for (i in 0 until numStepsPerFrame * 20) {
            computeTimeStep(timeScale, fluidSim.gravity, src, tmp)
        }

        glMemoryBarrier(GL_ALL_BARRIER_BITS)

        FramebufferToMemory.createImage(src, false, false)
            .write(desktop.getChild("h.png"))*/

        /* ImageWriter.writeImageFloat(w + 2, h + 2, "h.png", true, fh)

         val normalize = true
         ImageWriter.writeImageFloat(w + 2, h + 2, "h.png", normalize, fh)
         ImageWriter.writeImageFloat(w + 2, h + 2, "hu.png", normalize, hu)
         ImageWriter.writeImageFloat(w + 2, h + 2, "hv.png", normalize, hv)
         ImageWriter.writeImageFloat(w + 2, h + 2, "b.png", normalize, bh)*/

        val srcFB = Framebuffer("src", w2, h2, 1, 1, true, DepthBufferType.NONE)
        renderVideo(w2, h2, 30.0, desktop.getChild("height.mp4"), numFrames, srcFB) { callback ->
            for (i in 0 until numStepsPerFrame) {
                step(fluidSim.gravity, timeScale, src, tmp)
            }
            useFrame(srcFB) {
                val shader = showWavesShader.value
                shader.use()
                // todo compute maximum values, and divide
                shader.v3("scale", 1f, 0.01f, 0.01f)
                src.bind(0)
                GFX.flat01.draw(shader)
            }
            callback()
        }

        Engine.requestShutdown()

    }

}