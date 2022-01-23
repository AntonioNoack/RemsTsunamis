package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.OpenGL.renderPurely
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Clamping
import me.anno.gpu.texture.GPUFiltering
import me.anno.gpu.texture.Texture2D
import me.anno.maths.Maths.ceilDiv
import me.anno.tsunamis.engine.gpu.GraphicsEngine.Companion.synchronizeGraphics
import org.joml.Vector2i
import org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS
import org.lwjgl.opengl.GL42C.glMemoryBarrier

class SharedMemoryEngine(width: Int, height: Int) :
    GPUEngine<Texture2D>(width, height, {
        val tex = Texture2D(it, width, height, 1)
        tex.autoUpdateMipmaps = false
        tex.filtering = GPUFiltering.TRULY_NEAREST
        tex.clamping = Clamping.CLAMP
        tex
    }) {

    override fun createBuffer(buffer: Texture2D) {
        buffer.createFP32()
    }

    override fun createBuffer(buffer: Texture2D, data: FloatArray) {
        buffer.createRGBA(data, false)
    }

    override fun destroyBuffer(buffer: Texture2D) {
        buffer.destroy()
    }

    override fun step(gravity: Float, scaling: Float) {
        GFX.checkIsGFXThread()
        renderPurely {
            step(shaders.first, true, gravity, scaling, src, tmp)
            step(shaders.second, false, gravity, scaling, tmp, src)
        }
    }

    override fun halfStep(gravity: Float, scaling: Float, x: Boolean) {
        renderPurely {
            if (x) {
                step(shaders.first, true, gravity, scaling, src, tmp)
            } else {
                step(shaders.second, false, gravity, scaling, tmp, src)
            }
        }
    }

    override fun synchronize() {
        super.synchronize()
        GFX.checkIsGFXThread()
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        synchronizeGraphics()
    }

    override fun createFluidTexture(w: Int, h: Int, cw: Int, ch: Int) = src

    companion object {

        fun createTexture(name: String, width: Int, height: Int): Texture2D {
            val tex = Texture2D(name, width, height, 1)
            tex.autoUpdateMipmaps = false
            tex.filtering = GPUFiltering.TRULY_NEAREST
            tex.clamping = Clamping.CLAMP
            return tex
        }

        private val updateSize = 16
        private val writeSize = updateSize - 1

        private fun createShader(x: Boolean): ComputeShader {
            val p = if (x) "xyw" else "xzw"
            return ComputeShader(
                if (x) "computeTimeStep(x)" else "computeTimeStep(y)",
                Vector2i(updateSize), "" +
                        "precision highp float;\n" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 maxUV;\n" +
                        "uniform float timeScale;\n" +
                        "uniform float gravity;\n" +
                        GLSLSolver.fWaveSolverFull +
                        "shared vec4 updates[$updateSize * $updateSize];\n" +
                        "void main(){\n" +
                        "   ivec2 localUV = ivec2(gl_LocalInvocationID.xy);\n" +
                        "   ivec2 uv1 = localUV + ivec2(gl_WorkGroupID.xy) * ${
                            if (x) "ivec2($writeSize, $updateSize)"
                            else "ivec2($updateSize, $writeSize)"
                        };\n" +
                        // compute all updates inside the group
                        "   ivec2 deltaUV = ivec2(${if (x) "1,0" else "0,1"});\n" +
                        "   vec4 data0 = imageLoad(src, clamp(uv1 - deltaUV, ivec2(0), maxUV));\n" + // left/top
                        "   vec4 data1 = imageLoad(src, clamp(uv1,           ivec2(0), maxUV));\n" +
                        "   int index0 = ${if (x) "localUV.x * $updateSize + localUV.y" else "localUV.x + localUV.y * $updateSize"};\n" +
                        "   updates[index0] = timeScale * solve(data0.$p, data1.$p);\n" +
                        "   memoryBarrierShared();\n" + // synchronize all members
                        "   if(uv1.x <= maxUV.x && uv1.y <= maxUV.y && localUV.${if (x) "x" else "y"} < $writeSize){\n" +
                        // read the relevant updates from the group members
                        "       vec2 update = updates[index0].zw + updates[index0 + $updateSize].xy;\n" +
                        "       vec4 newData = data1 - vec4(update.x, ${if (x) "update.y, 0.0" else "0.0, update.y"}, 0.0);\n" +
                        "       if(newData.x < 0) newData.x = 0;\n" +
                        "       imageStore(dst, uv1, newData);\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        private val shaders by lazy { Pair(createShader(true), createShader(false)) }

        private fun step(
            shader: ComputeShader,
            x: Boolean,
            gravity: Float,
            timeScale: Float,
            src: Texture2D,
            dst: Texture2D
        ) {
            shader.use()
            shader.v1f("timeScale", timeScale)
            shader.v1f("gravity", gravity)
            shader.v2i("maxUV", src.w - 1, src.h - 1)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            if (x) {
                // we need extra groups on the x axis
                shader.runBySize(ceilDiv(src.w * updateSize, writeSize), src.h)
            } else {
                // extra groups, but for the y axis
                shader.runBySize(src.w, ceilDiv(src.h * updateSize, writeSize))
            }
        }

    }

}