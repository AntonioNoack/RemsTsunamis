package me.anno.tsunamis.gpu.compute

import me.anno.Engine
import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.useFrame
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeShader.Companion.bindTexture
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.shader.ShaderLib
import me.anno.gpu.shader.builder.Variable
import me.anno.gpu.texture.Texture2D
import me.anno.image.ImageWriter
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.aracluster.HeadlessContext
import me.anno.tsunamis.setups.CircularDiscontinuity
import me.anno.tsunamis.setups.LinearDiscontinuity
import me.anno.tsunamis.setups.NetCDFSetup
import me.anno.utils.OS.desktop
import me.anno.video.VideoCreator.Companion.renderVideo
import org.apache.logging.log4j.LogManager
import org.joml.Vector2i
import org.lwjgl.opengl.GL

object TsunamiShaders {

    // loggers are used to identify where log messages are coming from,
    // and to filter, if needed
    private val LOGGER = LogManager.getLogger(TsunamiShaders::class)

    // texture memory layout: height, momentum x, momentum y, bathymetry

    val fWaveSolverParams = "" +
            "   vec2 h  = vec2(data0.x, data1.x);\n" +
            "   vec2 hu = vec2(data0.y, data1.y);\n" +
            "   vec2 b  = vec2(data0.z, data1.z);\n"

    val fWaveSolverBase = "" + // memory layout: height, momentum, bathymetry
            // dry-wet boundary conditions
            "   if(h.x <= 0.0 && h.y <= 0.0){\n" +
            // "       h.x = 1.0, h.y = 1.0, hu.x = 0.0, hu.y = 0.0;\n" +
            "   } else if(h.x <= 0.0){\n" +
            "       h.x  = h.y;\n" +
            "       b.x  = b.y;\n" +
            "       hu.x = -hu.y;\n" +
            "   } else if(h.y <= 0.0){\n" +
            "       h.y  = h.x;\n" +
            "       b.y  = b.x;\n" +
            "       hu.y = -hu.x;\n" +
            "   }\n" +
            "   float roeHeight = (h.x+h.y)*0.5;\n" +
            "   vec2 sqrt01 = sqrt(h);\n" +
            // "   vec2 u = vec2(h.x > 0.0 ? hu.x / h.x : 0.0, h.y > 0.0 ? hu.y / h.y : 0.0);\n" +
            "   vec2 u = hu / h;\n" +
            "   float roeVelocity = (u.x * sqrt01.x + u.y * sqrt01.y) / (sqrt01.x + sqrt01.y);\n" +
            "   float gravityTerm = sqrt(gravity * roeHeight);\n" +
            "   vec2 lambda = vec2(roeVelocity - gravityTerm, roeVelocity + gravityTerm);\n" +
            "   float invLambda = 0.5 / gravityTerm;\n" +
            "   float bathymetryTerm = gravity * roeHeight * (b.y - b.x);\n" +
            "   float df0 = hu.y - hu.x;" +
            "   float df1 = hu.y * u.y - hu.x * u.x" +
            "                   + gravity * 0.5 * (h.y * h.y - h.x * h.x)" +
            "                   + bathymetryTerm;\n" +
            "   vec2 deltaH  = invLambda * vec2(df0 * lambda.y - df1, -df0 * lambda.x + df1);\n" +
            "   vec2 deltaHu = deltaH * lambda;\n"

    val fWaveSolverFull = "vec4 solve(vec3 data0, vec3 data1){\n" +
            fWaveSolverParams +
            "   if(h.x <= 0.0 && h.y <= 0.0) return vec4(0);\n" +
            fWaveSolverBase +
            "   vec4 dst = vec4(0.0);\n" +
            "   if(lambda.x < 0.0){\n" +
            "       dst.x += deltaH.x;\n" +
            "       dst.y += deltaHu.x;\n" +
            "   } else {\n" +
            "       dst.z += deltaH.x;\n" +
            "       dst.w += deltaHu.x;\n" +
            "   }\n" +
            "   if(lambda.y < 0.0){\n" +
            "       dst.x += deltaH.y;\n" +
            "       dst.y += deltaHu.y;\n" +
            "   } else {\n" +
            "       dst.z += deltaH.y;\n" +
            "       dst.w += deltaHu.y;\n" +
            "   }\n" +
            "   return dst;\n" +
            "}\n"

    val fWaveSolverHalf = "" +
            "vec2 solveXY(vec3 data0, vec3 data1){\n" +
            fWaveSolverParams +
            "   if(h.x <= 0.0 || h.y <= 0.0) return vec2(0);\n" +
            fWaveSolverBase +
            "   vec2 dst = vec2(0.0);\n" +
            "   if(lambda.x < 0.0){\n" +
            "       dst.x  = deltaH.x;\n" +
            "       dst.y  = deltaHu.x;\n" +
            "   }\n" +
            "   if(lambda.y < 0.0){\n" +
            "       dst.x += deltaH.y;\n" +
            "       dst.y += deltaHu.y;\n" +
            "   }\n" +
            "   return dst;\n" +
            "}\n" +
            "vec2 solveZW(vec3 data0, vec3 data1){\n" +
            fWaveSolverParams +
            "   if(h.x <= 0.0 || h.y <= 0.0) return vec2(0);\n" +
            fWaveSolverBase +
            "   vec2 dst = vec2(0.0);\n" +
            "   if(lambda.x > 0.0){\n" +
            "       dst.x  = deltaH.x;\n" +
            "       dst.y  = deltaHu.x;\n" +
            "   }\n" +
            "   if(lambda.y > 0.0){\n" +
            "       dst.x += deltaH.y;\n" +
            "       dst.y += deltaHu.y;\n" +
            "   }\n" +
            "   return dst;\n" +
            "}\n"

    // ghost outflow is handled by the clamping
    private fun timeStepShader(x: Boolean): ComputeShader {
        return ComputeShader(
            if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
            Vector2i(8, 8), "" +
                    "precision highp float;\n" +
                    "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                    "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                    "uniform vec2 textureSize;\n" +
                    "uniform float timeScale;\n" +
                    "uniform float gravity;\n" +
                    fWaveSolverFull +
                    fWaveSolverHalf +
                    "void main(){\n" +
                    "   if(gl_GlobalInvocationID.x < textureSize.x && gl_GlobalInvocationID.y < textureSize.y){\n" +
                    "       ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "       ivec2 uv0 = clamp(uv1 - ${if (x) "ivec2(1,0)" else "ivec2(0,1)"}, ivec2(0), ivec2(textureSize));\n" +
                    "       ivec2 uv2 = clamp(uv1 + ${if (x) "ivec2(1,0)" else "ivec2(0,1)"}, ivec2(0), ivec2(textureSize));\n" +
                    "       vec4 data0 = imageLoad(src, uv0);\n" +
                    "       vec4 data1 = imageLoad(src, uv1);\n" +
                    "       vec4 data2 = imageLoad(src, uv2);\n" +
                    "       vec2 update = timeScale * (${
                        if (x) "solveZW(data0.xyw, data1.xyw) + solveXY(data1.xyw, data2.xyw)"
                        else "  solveZW(data0.xzw, data1.xzw) + solveXY(data1.xzw, data2.xzw)"
                    });\n" +
                    "       vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" +
                    "       if(newData.x < 0) newData.x = 0;\n" +
                    "       imageStore(dst, uv1, newData);\n" +
                    "   }\n" +
                    "}\n"
        )
    }

    val computeTimeStep by lazy {
        Pair(timeStepShader(true), timeStepShader(false))
    }

    fun computeTimeStep(timeScale: Float, gravity: Float, src: Texture2D, tmp: Texture2D) {
        val (shaderX, shaderY) = computeTimeStep
        shaderX.use()
        shaderX.v2("textureSize", src.w.toFloat(), src.h.toFloat())
        shaderX.v1("timeScale", timeScale)
        shaderX.v1("gravity", gravity)
        bindTexture(0, src, ComputeTextureMode.READ)
        bindTexture(1, tmp, ComputeTextureMode.READ_WRITE)
        shaderX.runBySize(src.w, src.h)
        shaderY.use()
        shaderY.v2("textureSize", src.w.toFloat(), src.h.toFloat())
        shaderY.v1("timeScale", timeScale)
        shaderY.v1("gravity", gravity)
        bindTexture(0, tmp, ComputeTextureMode.READ)
        bindTexture(1, src, ComputeTextureMode.READ_WRITE)
        shaderY.runBySize(src.w, src.h)
    }

    val showWavesShader = ShaderLib.createShader(
        "copy", ShaderLib.simplestVertexShader, listOf(Variable("vec2", "uv")), "" +
                "uniform sampler2D tex;\n" +
                "void main(){\n" +
                "   vec4 data = texture(tex, uv);\n" +
                "   float surface = data.x + data.w;\n" +
                "   float mx = clamp(data.y * 0.01, 0, 1);\n" +
                "   float my = clamp(data.z * 0.01, 0, 1);\n" +
                "   gl_FragColor = vec4(vec3(surface) + vec3(1.0, 0.0, -1.0) * mx + vec3(-0.5, 1.0, -0.5) * my, 1.0);\n" +
                "}", listOf("tex")
    )

    // when a main function is inside an object or class,
    // @JvmStatic and Array<String> need to be added to the function
    // for it to be registered as an executable main function within Intellij Idea
    @JvmStatic
    fun main(args: Array<String>) {

        // 10800 x 6000
        // test the computation
        val w = 1024
        val h = 1024 / 2 // w * 60 / 108
        val numFrames = 500
        val numStepsPerFrame = 50

        val w2 = 1024
        val h2 = w2 * h / w

        HeadlessContext.createContext(w, h, true)
        // HiddenOpenGLContext.createOpenGL(w, h)
        ShaderLib.init()

        val fluidSim = FluidSim()
        fluidSim.width = w
        fluidSim.height = h
        fluidSim.cellSizeMeters = 100f

        when (1) {
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
                fluidSim.setup = setup
            }
        }

        val setup = fluidSim.setup ?: throw RuntimeException("Simulation needs setup")
        while (!fluidSim.ensureFieldSize()) {
            LOGGER.info("Waiting for setup to load")
            Thread.sleep(500)
        }

        LOGGER.info("Preferred size: ${setup.getPreferredNumCellsX()} x ${setup.getPreferredNumCellsY()}")

        val srcFB = Framebuffer("src", w2, h2, 1, 1, true, DepthBufferType.NONE)
        useFrame(srcFB) {}

        val src = Texture2D("src", w, h, 1)
        val tmp = Texture2D("tmp", w, h, 1)

        val fh = fluidSim.fluidHeight
        val hu = fluidSim.fluidMomentumX
        val hv = fluidSim.fluidMomentumY
        val bh = fluidSim.bathymetry

        fluidSim.setGhostOutflow(fh)
        fluidSim.setGhostOutflow(hu)
        fluidSim.setGhostOutflow(hv)
        fluidSim.setGhostOutflow(bh)

        val data = FloatArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (x + y * w) * 4
                val j = fluidSim.getIndex(x, y)
                data[i] = fh[j]
                data[i + 1] = hu[j]
                data[i + 2] = hv[j]
                data[i + 3] = bh[j]
            }
        }

        src.createRGBA(data, false)
        tmp.createRGBA(data, false)

        ImageWriter.writeImageFloat(w + 2, h + 2, "h.png", true, fh)

        val maxTimeStep = fluidSim.computeMaxTimeStep()
        val timeScale = maxTimeStep / fluidSim.cellSizeMeters

        // call barrier for memory read
        // glMemoryBarrier(GL_PIXELBUFFER_BARRIER_BIT)

        // glMemoryBarrier(GL_ALLBARRIER_BITS)
        /*ImageWriter.writeImageFloat(w + 2, h + 2, "h.png", false, fh)
        ImageWriter.writeImageFloat(w + 2, h + 2, "hu.png", false, hu)
        ImageWriter.writeImageFloat(w + 2, h + 2, "hv.png", false, hv)
        ImageWriter.writeImageFloat(w + 2, h + 2, "b.png", false, bh)*/

        /*renderVideo(w2, h2, 30.0, desktop.getChild("height.mp4"), numFrames, srcFB) {
            for (i in 0 until numStepsPerFrame) {
                computeTimeStep(timeScale, fluidSim.gravity, src, tmp)
            }
            useFrame(srcFB) {
                val shader = showWavesShader.value
                shader.use()
                src.bind(0)
                GFX.flat01.draw(shader)
            }
        }*/

        Engine.shutdown()

    }

}