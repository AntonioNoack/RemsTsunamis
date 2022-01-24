package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.gpu.ComputeEngine.Companion.createTexture
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.utils.types.Booleans.toInt
import org.joml.Vector2i
import org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42C.glMemoryBarrier

class TwoPassesEngine(width: Int, height: Int) : ComputeEngine(width, height) {

    private val delta = createTexture("delta", width + 1, height + 1)

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float) {
        super.init(sim, setup, gravity)
        createBuffer(delta)
    }

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        renderPurely {
            step(shaders0.first, shaders1.first, true, gravity, scaling, src, delta, tmp)
            step(shaders0.second, shaders1.second, false, gravity, scaling, tmp, delta, src)
        }
    }

    override fun halfStep(gravity: Float, scaling: Float, x: Boolean) {
        renderPurely {
            if (x) {
                step(shaders0.first, shaders1.first, x, gravity, scaling, src, delta, tmp)
            } else {
                step(shaders0.second, shaders1.second, x, gravity, scaling, tmp, delta, src)
            }
        }
    }

    override fun destroy() {
        super.destroy()
        delta.destroy()
    }

    companion object {

        private fun createShader0(x: Boolean): ComputeShader {
            return ComputeShader(
                if (x) "computeDelta(x)" else "computeDelta(y)",
                Vector2i(16), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUVIn, maxUVOut;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverFull +
                        "void main(){\n" +
                        "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv1.x <= maxUVOut.x && uv1.y <= maxUVOut.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec4 data0 = imageLoad(src, max(uv1 - deltaUV, ivec2(0)));\n" + // left/top
                        "       vec4 data1 = imageLoad(src, min(uv1, maxUVIn));\n" +
                        "       vec4 update = timeScale * (${// 2 flops
                            if (x) "solve(data0.xyw, data1.xyw)"
                            else "  solve(data0.xzw, data1.xzw)"
                        });\n" +
                        "       imageStore(dst, uv1, update);\n" + // total: 88 flops
                        "   }\n" +
                        "}\n"
            )
        }

        private fun createShader1(x: Boolean): ComputeShader {
            return ComputeShader(
                if (x) "computeTimeStep1(x)" else "computeTimeStep1(y)",
                Vector2i(16), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D ori;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 2) uniform image2D dst;\n" +
                        "uniform ivec2 maxUVIn, maxUVOut;\n" +
                        "void main(){\n" +
                        "   ivec2 uv1 = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv1.x <= maxUVOut.x && uv1.y <= maxUVOut.y){\n" +
                        "       ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "       vec4 data1 = imageLoad(src, uv1);\n" +
                        "       vec4 data2 = imageLoad(src, min(uv1 + deltaUV, maxUVIn));\n" + // right/bottom
                        "       vec2 update = data1.zw + data2.xy;\n" +
                        "       vec4 newData = imageLoad(ori, uv1) - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" +
                        "       if(newData.x <= 0.0) newData.xyz = vec3(0.0);\n" +
                        "       imageStore(dst, uv1, newData);\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders0 by lazy { Pair(createShader0(true), createShader0(false)) }
        private val shaders1 by lazy { Pair(createShader1(true), createShader1(false)) }

        private fun step(
            shader0: ComputeShader,
            shader1: ComputeShader,
            isX: Boolean,
            gravity: Float,
            timeScale: Float,
            src: Texture2D,
            tmp: Texture2D,
            dst: Texture2D
        ) {
            shader0.use()
            shader0.v1f("timeScale", timeScale)
            shader0.v1f("gravity", gravity)
            shader0.v2i("maxUVIn", src.w - 1, src.h - 1)
            shader0.v2i("maxUVOut", src.w - 1 + isX.toInt(), src.h - 1 + (!isX).toInt())
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, tmp, ComputeTextureMode.WRITE)
            if (isX) {
                // on the edge, the result will be zero, so we use 1 edge more
                shader0.runBySize(src.w + 1, src.h)
            } else {
                shader0.runBySize(src.w, src.h + 1)
            }
            shader1.use()
            shader1.v2i("maxUVIn", src.w - 1 + isX.toInt(), src.h - 1 + (!isX).toInt())
            shader1.v2i("maxUVOut", src.w - 1, src.h - 1)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, tmp, ComputeTextureMode.READ)
            ComputeShader.bindTexture(2, dst, ComputeTextureMode.WRITE)
            shader1.runBySize(src.w, src.h)
        }

    }

}