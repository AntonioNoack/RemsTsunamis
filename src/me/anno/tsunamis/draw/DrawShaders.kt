package me.anno.tsunamis.draw

import me.anno.gpu.shader.ComputeShader
import org.joml.Vector2i
import org.joml.Vector3i

class DrawShaders(comp16engine: Boolean, halfPrecisionBath: Boolean) {

    private val format = if (comp16engine) "r16f" else "rgba32f"
    private val format2 = if (halfPrecisionBath) "r16f" else "r32f"

    val drawShader by lazy {
        ComputeShader(
            "drawing", Vector3i(16, 16, 1), listOf(), "" +
                    "layout($format, binding = 0) uniform image2D src;\n" +
                    "layout($format, binding = 1) uniform image2D dst;\n" +
                    (if (comp16engine)
                        "layout($format2, binding = 2) uniform image2D bath;\n"
                    else "") +
                    // todo include previous wave front, and calculate delta to max amplitude
                    "uniform ivec2 inSize;\n" +
                    "uniform ivec2 offset;\n" +
                    "uniform vec2 v;\n" +
                    "uniform vec4 dw;\n" +
                    "uniform vec2 brush;\n" +
                    "float slerp(float x){\n" +
                    "   return x*x*(3.0-2.0*x);\n" +
                    "}\n" +
                    "float getNormalizedBrushShape(float d2){\n" +
                    "   return d2 < 1.0 ? slerp(1.0 - d2) : 0.0;\n" +
                    "}\n" +
                    "void main(){\n" +
                    "   ivec2 p0 = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(p0.x < inSize.x && p0.y < inSize.y){\n" +
                    "       ivec2 uv = p0 + offset;\n" +
                    "       vec2 p = vec2(uv);\n" +
                    "       vec2 dp = p - v;\n" +
                    "       float t = dot(dp, dw.zw);\n" +
                    "       if(t >= 0.0 && t < 1.0 ${
                        // if the type is Compute16Engine, we should not draw on the coast
                        if (comp16engine) "&& imageLoad(bath, uv).x <= 0.0" else ""
                    }){\n" +
                    "           dp -= t * dw.xy;\n" +
                    "           float d2 = dot(dp, dp);\n" +
                    "           float strength = brush.x * getNormalizedBrushShape(d2 * brush.y);\n" +
                    "           vec4 newData = imageLoad(src, uv);\n" +
                    "           newData.x += strength * (dp.y * dw.z - dp.x * dw.w);\n" +
                    (if (comp16engine)
                        "newData.x = max(bathymetry, newData.x);\n"
                    else
                        "newData.x = max(0.0, newData.x);\n") +
                    "           imageStore(dst, uv, newData);\n" +
                    "       }\n" +
                    "   }\n" +
                    "}"
        )
    }

    val copyShader by lazy {
        ComputeShader(
            "drawing-copy", Vector3i(16, 16, 1), emptyList(), "" +
                    "layout($format, binding = 0) uniform image2D src;\n" +
                    "layout($format, binding = 1) uniform image2D dst;\n" +
                    "uniform ivec2 inSize;\n" +
                    "uniform ivec2 offset;\n" +
                    "void main(){\n" +
                    "   ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n" +
                    "   if(p.x < inSize.x && p.y < inSize.y){\n" +
                    "       ivec2 uv = p + offset;\n" +
                    "       imageStore(dst, uv, imageLoad(src, uv));\n" +
                    "   }\n" +
                    "}"
        )
    }

}