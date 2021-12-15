package me.anno.tsunamis.gpu.compute

import me.anno.Engine
import me.anno.gpu.GFX
import me.anno.gpu.RenderState.useFrame
import me.anno.gpu.ShaderLib
import me.anno.gpu.framebuffer.DepthBufferType
import me.anno.gpu.framebuffer.Framebuffer
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeShader.Companion.bindTexture
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.setups.CircularDiscontinuity
import me.anno.utils.OS.desktop
import me.anno.video.FFMPEGEncodingBalance
import me.anno.video.FFMPEGEncodingType
import me.anno.video.VideoCreator
import org.joml.Vector2i
import org.lwjgl.opengl.ARBShaderImageLoadStore.GL_PIXEL_BUFFER_BARRIER_BIT
import org.lwjgl.opengl.GL44.glMemoryBarrier

// memory layout: height, momentum x, momentum y, bathymetry

val fWaveSolverBase = "" +
        "   vec2 h  = vec2(data0.x, data1.x);\n" +
        "   vec2 hu = vec2(data0.y, data1.y);\n" +
        "   vec2 b  = vec2(data0.z, data1.z);\n" +
        // dry-wet boundary conditions
        "   if(h.x <= 0.0){\n" +
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
        "   vec2 u = vec2(h.x > 0.0 ? hu.x / h.x : 0.0, h.y > 0.0 ? hu.y / h.y : 0.0);\n" +
        "   float roeVelocity = (u.x * sqrt01.x + u.y * sqrt01.y) / (sqrt01.x + sqrt01.y);\n" +
        "   float gravityTerm = sqrt(gravity * roeHeight);\n" +
        "   vec2 lambda = roeVelocity + vec2(-gravityTerm, +gravityTerm);\n" +
        "   float invLambda = 0.5 / gravityTerm;\n" +
        "   float bathymetryTerm = roeHeight * (b.y - b.x);\n" +
        "   vec2 df = vec2(hu.y - hu.x, hu.y * u.y - hu.x + gravity * (0.5 * (h.y * h.y - h.x * h.x) + bathymetryTerm));\n" +
        "   vec2 deltaH  = invLambda * (vec2(df.x * lambda.x, -df.y * lambda.y) - df.y);\n" +
        "   vec2 deltaHu = deltaH * lambda;\n"

val fWaveSolverFull = "vec4 solve(vec3 data0, vec3 data1){\n" +
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

val fWaveSolverHalf = "vec2 solve(vec3 data0, vec3 data1){\n" +
        fWaveSolverBase +
        "   vec2 dst;\n" +
        "   if(lambda.x < 0.0){\n" +
        "       dst.x = deltaH.x;\n" +
        "       dst.y = deltaHu.x;\n" +
        "   } else dst = vec2(0.0);\n" +
        "   if(lambda.y < 0.0){\n" +
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
                // fWaveSolverFull +
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
                    if (x) "solve(data2.xyw, data1.xyw) + solve(data1.xyw, data0.xyw)"
                    else "  solve(data2.xzw, data1.xzw) + solve(data1.xzw, data0.xzw)"
                });\n" +
                /*"       vec2 update = timeScale * (${
                    if (x) "solve(data0.xyw, data1.xyw).zw + solve(data1.xyw, data2.xyw).xy"
                    else "  solve(data0.xzw, data1.xzw).zw + solve(data1.xzw, data2.xzw).xy"
                });\n" +*/
                "       imageStore(dst, uv1, vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0) + data1);\n" +
                // "       imageStore(dst, uv1, (data0 + data1 + data2)/3.0);\n" +
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

fun renderVideo(w: Int, h: Int, fps: Double, numIterations: Int, fb: Framebuffer, update: () -> Unit) {
    val creator = VideoCreator(
        w, h, fps, numIterations + 1L, FFMPEGEncodingBalance.S1,
        FFMPEGEncodingType.DEFAULT, desktop.getChild("height.mp4")
    )
    creator.init()
    var frameCount = 0
    fun writeFrame() {
        creator.writeFrame(fb, frameCount.toLong()) {
            frameCount++
            if (frameCount <= numIterations) {
                GFX.addGPUTask(1) {
                    update()
                    writeFrame()
                }
            } else {
                creator.close()
                Engine.shutdown()
            }
        }
    }
    GFX.addGPUTask(1) { writeFrame() }
    while (!Engine.shutdown) {
        GFX.workGPUTasks(true)
    }
}

fun main() {

    // test the computation
    val w = 300
    val h = 300
    val numIterations = 500

    HiddenOpenGLContext.createOpenGL(w, h)
    ShaderLib.init()

    val fluidSim = FluidSim()
    fluidSim.width = w
    fluidSim.height = h

    val setup = CircularDiscontinuity()
    setup.heightInner = 0.5f
    setup.heightOuter = 0.8f
    setup.impulseInner = 0f
    setup.impulseOuter = 0f
    setup.hasBorder = true
    setup.borderHeight = 0.9f
    fluidSim.setup = setup

    fluidSim.ensureFieldSize()

    val srcFB = Framebuffer("src", w, h, 1, 1, true, DepthBufferType.NONE)
    useFrame(srcFB) {}

    val src = srcFB.getColor0()
    val tmp = Texture2D("tmp", w, h, 1)

    val fh = fluidSim.fluidHeight
    val hu = fluidSim.fluidMomentumX
    val hv = fluidSim.fluidMomentumY
    val bh = fluidSim.bathymetry

    fluidSim.setGhostOutflow(fh)
    fluidSim.setGhostOutflow(hu)
    fluidSim.setGhostOutflow(hv)
    fluidSim.setGhostOutflow(bh)
    fluidSim.cellSizeMeters = 100f

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

    val maxTimeStep = fluidSim.computeMaxTimeStep()
    val timeScale = maxTimeStep / fluidSim.cellSizeMeters

    // call barrier for memory read
    glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT)

    // glMemoryBarrier(GL_ALL_BARRIER_BITS)
    /*ImageWriter.writeImageFloat(w + 2, h + 2, "h.png", false, fh)
    ImageWriter.writeImageFloat(w + 2, h + 2, "hu.png", false, hu)
    ImageWriter.writeImageFloat(w + 2, h + 2, "hv.png", false, hv)
    ImageWriter.writeImageFloat(w + 2, h + 2, "b.png", false, bh)*/

    renderVideo(w, h, 30.0, numIterations, srcFB) {
        computeTimeStep(timeScale, fluidSim.gravity, src, tmp)
    }

    /* nextFrame()
     for (i in 0 until numIterations) {
         computeTimeStep(scale, fluidSim.gravity, src, tmp)
         creator.writeFrame(srcFB, i + 1L) {}
     }
     FramebufferToMemory.cloneFromFramebuffer()
     val srcI = FramebufferToMemory.createImage(src, false, withAlpha = false)
     ComponentImage(srcI, false, 'r').write(desktop.getChild("src.png"))
     Engine.shutdown()*/
}