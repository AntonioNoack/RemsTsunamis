package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.GFXState.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.shader.GLSLType
import me.anno.gpu.shader.builder.Variable
import me.anno.gpu.texture.Texture2D
import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.setups.FluidSimSetup
import me.anno.utils.types.Booleans.toInt
import org.joml.Vector3i

class TwoPassesEngine(width: Int, height: Int) : ComputeEngine(width, height) {

    private val delta = createTexture("delta", width + 1, height + 1)

    override fun init(sim: FluidSim?, setup: FluidSimSetup, gravity: Float, minFluidHeight: Float) {
        super.init(sim, setup, gravity, minFluidHeight)
        createBuffer(delta)
    }

    override fun step(gravity: Float, scaling: Float, minFluidHeight: Float) {
        GFX.checkIsGFXThread()
        renderPurely {
            step(shaders0.first, shaders1.first, true, gravity, scaling, minFluidHeight, src, delta, tmp)
            step(shaders0.second, shaders1.second, false, gravity, scaling, minFluidHeight, tmp, delta, src)
        }
    }

    override fun halfStep(gravity: Float, scaling: Float, minFluidHeight: Float, x: Boolean) {
        renderPurely {
            if (x) {
                step(shaders0.first, shaders1.first, x, gravity, scaling, minFluidHeight, src, delta, tmp)
            } else {
                step(shaders0.second, shaders1.second, x, gravity, scaling, minFluidHeight, tmp, delta, src)
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
                Vector3i(16, 16, 1), listOf(
                    Variable(GLSLType.V2I, "maxUVIn"),
                    Variable(GLSLType.V2I, "maxUVOut"),
                    Variable(GLSLType.V1F, "timeScale"),
                    Variable(GLSLType.V1F, "gravity"),
                    Variable(GLSLType.V1F, "minFluidHeight")
                ), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
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
                Vector3i(16, 16, 1), listOf(
                    Variable(GLSLType.V2I, "maxUVIn"),
                    Variable(GLSLType.V2I, "maxUVOut"),
                ), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D ori;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 2) uniform image2D dst;\n" +
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
            minFluidHeight: Float,
            src: Texture2D,
            tmp: Texture2D,
            dst: Texture2D
        ) {
            shader0.use()
            initShader(shader0, timeScale, gravity, minFluidHeight, src)
            shader0.v2i("maxUVIn", src.width - 1, src.height - 1)
            shader0.v2i("maxUVOut", src.width - 1 + isX.toInt(), src.height - 1 + (!isX).toInt())
            shader0.bindTexture(0, src, ComputeTextureMode.READ)
            shader0.bindTexture(1, tmp, ComputeTextureMode.WRITE)
            // the result will be zero on the edge, so we use 1 row more
            shader0.runBySize(src.width + isX.toInt(), src.height + (!isX).toInt())
            shader1.use()
            shader1.v2i("maxUVIn", src.width - 1 + isX.toInt(), src.height - 1 + (!isX).toInt())
            shader1.v2i("maxUVOut", src.width - 1, src.height - 1)
            shader1.bindTexture(0, src, ComputeTextureMode.READ)
            shader1.bindTexture(1, tmp, ComputeTextureMode.READ)
            shader1.bindTexture(2, dst, ComputeTextureMode.WRITE)
            shader1.runBySize(src.width, src.height)
        }

    }

}