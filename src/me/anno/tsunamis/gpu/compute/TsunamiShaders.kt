package me.anno.tsunamis.gpu.compute

import me.anno.gpu.GFX
import me.anno.gpu.copying.FramebufferToMemory
import me.anno.gpu.hidden.HiddenOpenGLContext
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeShader.Companion.bindTexture
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.setups.LinearDiscontinuity
import me.anno.utils.OS.desktop
import org.joml.Vector2i
import org.lwjgl.opengl.GL44.*

// memory layout: height, momentum x, momentum y, bathymetry

val fWaveSolver = "vec4 solve(vec3 data0, vec3 data1){\n" +
        "   vec2 h  = vec2(data0.x, data1.x);\n" +
        "   vec2 hu = vec2(data0.y, data1.y);\n" +
        "   vec2 b  = vec2(data0.z, data1.z);\n" +
        "   float roeHeight = (h.x+h.y)*0.5;\n" +
        "   vec2 sqrt01 = sqrt(h);\n" +
        "   vec2 u = hu / h;\n" +
        "   float roeVelocity = (u.x * sqrt01.x + u.y * sqrt01.y) / (sqrt01.x + sqrt01.y);\n" +
        "   float gravityTerm = sqrt(gravity * roeHeight);\n" +
        "   vec2 lambda = roeVelocity + vec2(-gravityTerm, +gravityTerm);\n" +
        "   float invLambda = 0.5 / gravityTerm;\n" +
        "   float bathymetryTerm = roeHeight * (b.y - b.x);\n" +
        "   vec2 df = vec2(hu.y - hu.x, hu.y * u.y - hu.x + gravity * (0.5 * (h.y * h.y - h.x * h.x) + bathymetryTerm));\n" +
        "   vec2 deltaH  = invLambda * (vec2(df.x * lambda.x, -df.y * lambda.y) - df.y);\n" +
        "   vec2 deltaHu = deltaH * lambda;\n" +
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

// ghost outflow is handled by the clamping
private fun timeStepShader(x: Boolean): ComputeShader {
    return ComputeShader(
        if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
        Vector2i(8, 8), "" +
                "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                "uniform float timeScale;\n" +
                "uniform float gravity;\n" +
                fWaveSolver +
                "void main(){\n" +
                "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                "   ivec2 uv0 = uv1 - ${if (x) "ivec2(1,0)" else "ivec2(0,1)"};\n" +
                "   vec4 data0 = imageLoad(src, uv0);\n" +
                "   vec4 data1 = imageLoad(src, uv1);\n" +
                "   vec4 update = timeScale * ${if (x) "solve(data0.xyw, data1.xyw)" else "solve(data0.xzw, data1.xzw)"};\n" +
                "   vec4 delta0 = vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0) + data0;\n" +
                "   vec4 delta1 = vec4(update.z, ${if (x) "update.w, 0.0" else "0.0, update.w"}, 0.0);\n" +
                "   imageAtomicAdd(dst, uv0, delta0);\n" +
                "   imageAtomicAdd(dst, uv1, delta1);\n" +
                "}"
    )
}

val computeTimeStep by lazy {
    Pair(timeStepShader(true), timeStepShader(false))
}

fun computeTimeStep(scale: Float, gravity: Float, src: Texture2D, tmp: Texture2D) {
    val (shaderX, shaderY) = computeTimeStep
    GFX.check()
    glClearTexImage(tmp.pointer, 0, GL_RGBA, GL_FLOAT, FloatArray(4))
    GFX.check()
    glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT)
    GFX.check()
    shaderX.use()
    bindTexture(0, src, ComputeTextureMode.READ)
    bindTexture(1, tmp, ComputeTextureMode.READ_WRITE)
    shaderX.runBySize(src.w, src.h)
    glClearTexImage(src.pointer, 0, GL_RGBA32F, GL_FLOAT, FloatArray(4))
    glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT)
    shaderY.use()
    GFX.check()
    bindTexture(0, tmp, ComputeTextureMode.READ)
    bindTexture(1, src, ComputeTextureMode.READ_WRITE)
    GFX.check()
    shaderY.runBySize(src.w, src.h)
    GFX.check()
}

fun main() {
    // test the computation
    val w = 100
    val h = 100
    HiddenOpenGLContext.createOpenGL(w, h)
    val fluidSim = FluidSim()
    fluidSim.width = w
    fluidSim.height = h
    val setup = LinearDiscontinuity()
    fluidSim.setup = setup
    fluidSim.ensureFieldSize()
    val src = Texture2D("src", w, h, 1)
    val tmp = Texture2D("tmp", w, h, 1)
    val data = FloatArray(w * h * 4)
    val fh = fluidSim.fluidHeight
    val hu = fluidSim.fluidMomentumX
    val hv = fluidSim.fluidMomentumY
    val bh = fluidSim.bathymetry
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
    val scale = maxTimeStep / fluidSim.cellSizeMeters
    for (i in 0 until 100) {
        computeTimeStep(scale, fluidSim.gravity, src, tmp)
    }
    // call barrier for memory read
    // glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT)
    glMemoryBarrier(GL_ALL_BARRIER_BITS)
    val image = FramebufferToMemory.createImage(src, false, withAlpha = false)
    image.write(desktop.getChild("fluid.png"))
}