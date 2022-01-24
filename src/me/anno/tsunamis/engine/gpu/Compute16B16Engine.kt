package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.TargetType
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.gpu.GPUEngine.Companion.initTexture
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import me.anno.tsunamis.setups.FluidSimSetup
import org.joml.Vector2i
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL30C.*

/**
 * this is an engine, which uses fp16 values instead of fp32, and saves the surface height instead of the fluid height;
 * the hope of this experiment was to minimize data transfers
 * */
class Compute16B16Engine(width: Int, height: Int) : CPUEngine(width, height) {

    /**
     * two cycles:
     * s0,hx0,bath -> s1,hx1,bath
     * s1,hy0,bath -> s0,hy1,bath
     * s0,hx1,bath -> s1,hx0,bath
     * s1,hy1,bath -> s0,hy0,bath
     * */

    private val bathymetryTex = Texture2D("bath", width, height, 1)

    private val surface0 = Texture2D("s16-0", width, height, 1)
    private val surface1 = Texture2D("s16-1", width, height, 1)
    private val momentumX0 = Texture2D("mx16-0", width, height, 1)
    private val momentumX1 = Texture2D("mx16-1", width, height, 1)
    private val momentumY0 = Texture2D("my16-0", width, height, 1)
    private val momentumY1 = Texture2D("my16-1", width, height, 1)

    // can be replaced, if we adjust our render shader
    private val rendered = Texture2D("rend16", width, height, 1)

    private var isEvenIteration = true
    private var hasChanged = false

    private var maxVelocity = 0f

    override fun supportsAsyncCompute(): Boolean = false
    override fun supportsMesh(): Boolean = false
    override fun supportsTexture(): Boolean = true
    override fun computeMaxVelocity(gravity: Float): Float = maxVelocity

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float) {

        super.init(sim, setup, gravity)
        maxVelocity = super.computeMaxVelocity(gravity)
        if (sim != null) super.updateStatistics(sim)

        GFX.check()

        val data = GLSLSolver.createTextureData(width, height, this)
        initTexture(rendered)
        rendered.createRGBA(data, false)

        GFX.check()

        for (tex in listOf(
            surface0,
            surface1,
            momentumX0,
            momentumX1,
            momentumY0,
            momentumY1
        )) {
            initTexture(tex)
            tex.create(FP16x1)
        }

        GFX.check()

        initTexture(bathymetryTex)
        bathymetryTex.create(
            if (bathymetryHalf) FP16x1
            else TargetType.FloatTarget1
        )

        GFX.check()

        val shader = splitShader
        shader.use()

        GFX.check()

        shader.v2i("maxUV", width - 1, height - 1)

        GFX.check()

        // bind all textures
        ComputeShader.bindTexture(0, rendered, ComputeTextureMode.READ, GL_RGBA32F)
        ComputeShader.bindTexture(1, surface0, ComputeTextureMode.WRITE, GL_R16F)
        ComputeShader.bindTexture(2, momentumX0, ComputeTextureMode.WRITE, GL_R16F)
        ComputeShader.bindTexture(3, momentumY0, ComputeTextureMode.WRITE, GL_R16F)
        ComputeShader.bindTexture(4, bathymetryTex, ComputeTextureMode.WRITE, if (bathymetryHalf) GL_R16F else GL_R32F)

        GFX.check()

        shader.runBySize(width, height)

        GFX.check()

    }

    override fun destroy() {
        super.destroy()
        bathymetryTex.destroy()
        surface0.destroy()
        surface1.destroy()
        momentumX0.destroy()
        momentumX1.destroy()
        momentumY0.destroy()
        momentumY1.destroy()
        rendered.destroy()
    }

    override fun halfStep(gravity: Float, scaling: Float, x: Boolean) {
        if (x) {
            step(
                shaders.first,
                surface0, if (isEvenIteration) momentumX0 else momentumX1,
                surface1, if (isEvenIteration) momentumX1 else momentumX0,
                bathymetryTex, gravity, scaling
            )
        } else {
            step(
                shaders.second,
                surface1, if (isEvenIteration) momentumY0 else momentumY1,
                surface0, if (isEvenIteration) momentumY1 else momentumY0,
                bathymetryTex, gravity, scaling
            )
            isEvenIteration = !isEvenIteration
        }
        hasChanged = true
    }

    override fun synchronize() {
        super.synchronize()
        synchronizeGraphics()
    }

    override fun createFluidTexture(w: Int, h: Int, cw: Int, ch: Int): Texture2D {
        GFX.checkIsGFXThread()
        // todo this step could reduce the resolution for faster rendering
        if (hasChanged) {
            // render partial data into rendered
            val shader = mergeShader
            shader.use()
            shader.v2i("maxUV", width - 1, height - 1)
            // bind all textures
            ComputeShader.bindTexture(0, rendered, ComputeTextureMode.WRITE)
            ComputeShader.bindTexture(1, surface0, ComputeTextureMode.READ, GL_R16F)
            ComputeShader.bindTexture(
                2,
                if (isEvenIteration) momentumX0 else momentumX1,
                ComputeTextureMode.READ,
                GL_R16F
            )
            ComputeShader.bindTexture(
                3,
                if (isEvenIteration) momentumY0 else momentumY1,
                ComputeTextureMode.READ,
                GL_R16F
            )
            ComputeShader.bindTexture(
                4,
                bathymetryTex,
                ComputeTextureMode.READ,
                if (bathymetryHalf) GL_R16F else GL_R32F
            )
            shader.runBySize(width, height)
            hasChanged = false
        }
        return rendered
    }

    override fun updateStatistics(sim: FluidSim) {

    }

    companion object {

        private const val bathymetryHalf = true
        private val bty = if (bathymetryHalf) "r16f" else "r32f"

        private val FP16x1 = TargetType("fp16x1", GL_R16F, GL30.GL_RED, GL_HALF_FLOAT, 2, 1, true)

        private val splitShader = ComputeShader(
            "split16", Vector2i(16), "" +
                    "precision highp float;\n" +
                    "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                    "layout(r16f, binding = 1) uniform image2D dstSurface;\n" +
                    "layout(r16f, binding = 2) uniform image2D dstMomentumX;\n" +
                    "layout(r16f, binding = 3) uniform image2D dstMomentumY;\n" +
                    "layout($bty, binding = 4) uniform image2D dstBath;\n" +
                    "uniform ivec2 maxUV;\n" +
                    "void main(){\n" +
                    "   ivec2 uv = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(uv.x <= maxUV.x && uv.y <= maxUV.y){\n" +
                    "       vec4 data = imageLoad(src, uv);\n" +
                    "       imageStore(dstSurface,   uv, vec4(data.r + data.a));\n" +
                    "       imageStore(dstMomentumX, uv, vec4(data.y));\n" +
                    "       imageStore(dstMomentumY, uv, vec4(data.z));\n" +
                    "       imageStore(dstBath,      uv, vec4(data.a));\n" +
                    "   }\n" +
                    "}"
        )

        private val mergeShader = ComputeShader(
            "merge16", Vector2i(16), "" +
                    "precision highp float;\n" +
                    "layout(rgba32f, binding = 0) uniform image2D dst;\n" +
                    "layout(r16f, binding = 1) uniform image2D srcSurface;\n" +
                    "layout(r16f, binding = 2) uniform image2D srcMomentumX;\n" +
                    "layout(r16f, binding = 3) uniform image2D srcMomentumY;\n" +
                    "layout($bty, binding = 4) uniform image2D srcBath;\n" +
                    "uniform ivec2 maxUV;\n" +
                    "void main(){\n" +
                    "   ivec2 uv = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(uv.x <= maxUV.x && uv.y <= maxUV.y){\n" +
                    "       float dh   = imageLoad(srcSurface,   uv).x;\n" +
                    "       float mx   = imageLoad(srcMomentumX, uv).x;\n" +
                    "       float my   = imageLoad(srcMomentumY, uv).x;\n" +
                    "       float bath = imageLoad(srcBath,      uv).x;\n" +
                    "       imageStore(dst, uv, vec4(dh-bath, mx, my, bath));\n" +
                    "   }\n" +
                    "}"
        )

        private fun createShader(x: Boolean): ComputeShader {
            return ComputeShader(
                if (x) "computeTimeStep16(x)" else "computeTimeStep16(y)",
                Vector2i(16), "" +
                        "precision highp float;\n" +
                        "layout(r16f, binding = 0) uniform image2D srcSurface;\n" +
                        "layout(r16f, binding = 1) uniform image2D srcMomentum;\n" +
                        "layout(r16f, binding = 2) uniform image2D dstSurface;\n" +
                        "layout(r16f, binding = 3) uniform image2D dstMomentum;\n" +
                        "layout($bty, binding = 4) uniform image2D srcBathymetry;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverHalf +
                        "vec3 load(ivec2 uv){\n" +
                        "   float surface  = imageLoad(srcSurface, uv).x;\n" +
                        "   float momentum = imageLoad(srcMomentum, uv).x;\n" +
                        "   float bath     = imageLoad(srcBathymetry, uv).x;\n" +
                        "   return vec3(surface - bath, momentum, bath);\n" +
                        "}\n" +
                        "void main(){\n" +
                        "   ivec2 uv = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv.x <= maxUV.x && uv.y <= maxUV.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec3 data0 = load(max(uv - deltaUV, ivec2(0)));\n" + // left/top
                        "       vec3 data1 = load(uv);\n" +
                        "       vec3 data2 = load(min(uv + deltaUV, maxUV));\n" + // right/bottom
                        "       vec2 update = timeScale * (solveZW(data0, data1) + solveXY(data1, data2));\n" +
                        "       vec2 newData = data1.xy - update;\n" +
                        "       if(newData.x < 0) newData.x = 0;\n" +
                        "       imageStore(dstSurface,  uv, vec4(newData.x + data1.z));\n" +
                        "       imageStore(dstMomentum, uv, vec4(newData.y));\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(
            shader: ComputeShader,
            srcSurface: Texture2D,
            srcMomentum: Texture2D,
            dstSurface: Texture2D,
            dstMomentum: Texture2D,
            bathymetry: Texture2D,
            gravity: Float,
            timeScale: Float
        ) {
            shader.use()
            shader.v1f("gravity", gravity)
            shader.v1f("timeScale", timeScale)
            shader.v2i("maxUV", srcSurface.w - 1, srcSurface.h - 1)
            ComputeShader.bindTexture(0, srcSurface, ComputeTextureMode.READ, GL_R16F)
            ComputeShader.bindTexture(1, srcMomentum, ComputeTextureMode.READ, GL_R16F)
            ComputeShader.bindTexture(2, dstSurface, ComputeTextureMode.WRITE, GL_R16F)
            ComputeShader.bindTexture(3, dstMomentum, ComputeTextureMode.WRITE, GL_R16F)
            ComputeShader.bindTexture(4, bathymetry, ComputeTextureMode.READ, if (bathymetryHalf) GL_R16F else GL_R32F)
            shader.runBySize(srcSurface.w, srcSurface.h)
        }

    }

}