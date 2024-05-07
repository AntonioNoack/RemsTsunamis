package me.anno.tsunamis.engine.gpu

import me.anno.gpu.shader.ComputeShader
import org.joml.Vector2i
import org.joml.Vector3i

class Compute16Shaders private constructor(bathymetryHalf: Boolean) {

    private val bty = if (bathymetryHalf) "r16f" else "r32f"

    val splitShader = ComputeShader(
        "split16", Vector3i(16, 16, 1), listOf(), "" +
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

    val mergeShader = ComputeShader(
        "merge16", Vector3i(16, 16, 1), listOf(), "" +
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
            Vector3i(16, 16, 1), listOf(), "" +
                    "precision highp float;\n" +
                    "layout(r16f, binding = 0) uniform image2D srcSurface;\n" +
                    "layout(r16f, binding = 1) uniform image2D srcMomentum;\n" +
                    "layout(r16f, binding = 2) uniform image2D dstSurface;\n" +
                    "layout(r16f, binding = 3) uniform image2D dstMomentum;\n" +
                    "layout($bty, binding = 4) uniform image2D srcBathymetry;\n" +
                    "uniform ivec2 maxUV;\n" +
                    "uniform float timeScale;\n" +
                    "uniform float gravity;\n" +
                    "uniform float minFluidHeight;\n" +
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

    val updateShaders by lazy { Pair(createShader(true), createShader(false)) }

    companion object {
        /**
         * shaders with 16/32 bit bathymetry
         * */
        val b16Shaders = Compute16Shaders(true)
        val b32Shaders = Compute16Shaders(false)
    }

}