package me.anno.tsunamis.engine.gpu

import me.anno.gpu.GFX
import me.anno.gpu.framebuffer.FBStack
import me.anno.gpu.framebuffer.TargetType
import me.anno.gpu.shader.ComputeShader
import me.anno.gpu.shader.ComputeTextureMode
import me.anno.gpu.texture.Texture2D
import me.anno.maths.Maths.ceilDiv
import org.joml.Vector2i
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20.glUniform2i
import org.lwjgl.opengl.GL42C
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Reduction {

    data class Operation(
        val name: String,
        val startValue: String,
        val function: String
    )

    /** finds the maximum value of all, so the brightest pixel */
    val MAX = Operation("max", "1e-38", "max(a,b)")

    /** finds the minimum value of all, so the darkest pixel */
    val MIN = Operation("min", "1e38", "min(a,b)")

    /** finds the sum of all pixels */
    val SUM = Operation("sum", "0.0", "a+b")

    /** finds the maximum amplitude */
    val MAX_ABS = Operation("max-abs", "0.0", "max(abs(a),abs(b))")

    /** finds the maximum amplitude of the surface where water is */
    val MAX_RA = Operation("max-ra", "0.0", "max(a, vec4(b.x > 0.0 ? abs(b.x + b.w) : 0.0, b.yz, 0.0))")

    private const val reduction = 16

    private val buffer = ByteBuffer.allocateDirect(4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val shaderByType = HashMap<Operation, ComputeShader>()

    fun reduce(texture: Texture2D, op: Operation): Vector4f {

        val shader = shaderByType.getOrPut(op) {
            ComputeShader(
                "reduce-${op.name}", Vector2i(16, 16), "" +
                        "layout(rgba32f, binding = 0) uniform image2D src;\n" +
                        "layout(rgba32f, binding = 1) uniform image2D dst;\n" +
                        "uniform ivec2 inSize, outSize;\n" +
                        "#define reduce(a,b) ${op.function}\n" +
                        "void main(){\n" +
                        "   ivec2 uv = ivec2(gl_GlobalInvocationID.xy);\n" +
                        "   if(uv.x < outSize.x && uv.y < outSize.y){\n" +
                        "       ivec2 uv0 = uv * $reduction, uv1 = min(uv0 + $reduction, inSize);\n" +
                        "       vec4 result = vec4(${op.startValue});\n" +
                        // strided access is more efficient on GPUs, so iterate over y
                        "       for(int x=uv0.x;x<uv1.x;x++){\n" +
                        "           for(int y=uv0.y;y<uv1.y;y++){\n" +
                        "               vec4 value = imageLoad(src, ivec2(x,y));\n" +
                        "               result = reduce(result, value);\n" +
                        "           }\n" +
                        "       }\n" +
                        "       imageStore(dst, uv, result);\n" +
                        "   }\n" +
                        "}\n"
            )
        }

        var src = texture
        while (src.w > reduction || src.h > reduction) {
            // reduce
            shader.use()
            val w = ceilDiv(src.w, reduction)
            val h = ceilDiv(src.h, reduction)
            val dstBuffer = FBStack["red", w, h, TargetType.FloatTarget4, 1, false]
            dstBuffer.ensure()
            val dst = dstBuffer.getColor0()
            glUniform2i(shader.getUniformLocation("inSize"), src.w, src.h)
            glUniform2i(shader.getUniformLocation("outSize"), w, h)
            ComputeShader.bindTexture(0, src, ComputeTextureMode.READ)
            ComputeShader.bindTexture(1, dst, ComputeTextureMode.WRITE)
            shader.runBySize(w, h)
            src = dst
        }

        // read pixel
        GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS)
        GL11.glFlush(); GL11.glFinish() // wait for everything to be drawn
        buffer.position(0)
        src.bind(0)
        GL11.glGetTexImage(src.target, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer)
        GFX.check()

        return Vector4f(buffer[0], buffer[1], buffer[2], buffer[3])
    }

}