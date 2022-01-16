package me.anno.tsunamis.engine.gpu

import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.TsunamiEngine

object GLSLSolver {

    /**
     * texture memory layout: height, momentum x, momentum y, bathymetry
     * ghost outflow is handled by the clamping
     * */

    private const val fWaveSolverParams = "" +
            "   vec2 h  = vec2(data0.x, data1.x);\n" +
            "   vec2 hu = vec2(data0.y, data1.y);\n" +
            "   vec2 b  = vec2(data0.z, data1.z);\n"

    private const val fWaveSolverBase = "" + // memory layout: height, momentum, bathymetry
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

    const val fWaveSolverFull = "vec4 solve(vec3 data0, vec3 data1){\n" +
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

    const val fWaveSolverHalf = "" +
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


    fun createTextureData(
        w: Int, h: Int,
        engine: CPUEngine
    ): FloatArray {
        return createTextureData(
            w, h,
            engine.fluidHeight,
            engine.fluidMomentumX,
            engine.fluidMomentumY,
            engine.bathymetry
        )
    }

    fun createTextureData(
        w: Int, h: Int,
        fluidHeight: FloatArray,
        momentumX: FloatArray,
        momentumY: FloatArray,
        bathymetry: FloatArray
    ): FloatArray {
        val data = FloatArray(w * h * 4)
        for (y in 0 until h) {
            var srcIndex = TsunamiEngine.getIndex(0, y, w, h)
            var dstIndex = (y * w) * 4
            for (x in 0 until w) {
                data[dstIndex++] = fluidHeight[srcIndex]
                data[dstIndex++] = momentumX[srcIndex]
                data[dstIndex++] = momentumY[srcIndex]
                data[dstIndex++] = bathymetry[srcIndex]
                srcIndex++
            }
        }
        return data
    }


    fun createTextureData(
        w: Int, h: Int,
        cw: Int, ch: Int,
        engine: CPUEngine
    ): FloatArray {
        return createTextureData(
            w, h, cw, ch,
            engine.fluidHeight,
            engine.fluidMomentumX,
            engine.fluidMomentumY,
            engine.bathymetry
        )
    }

    fun createTextureData(
        w: Int, h: Int,
        cw: Int, ch: Int,
        fluidHeight: FloatArray,
        fluidMomentumX: FloatArray,
        fluidMomentumY: FloatArray,
        bathymetry: FloatArray
    ): FloatArray {
        val data = FloatArray((cw - 2) * (ch - 2) * 4)
        var j = 0
        for (y in 1 until ch - 1) {
            for (x in 1 until cw - 1) {
                val k = FluidSim.coarseIndexToFine(w, h, cw, ch, x + y * cw)
                data[j + 0] = fluidHeight[k]
                data[j + 1] = fluidMomentumX[k]
                data[j + 2] = fluidMomentumY[k]
                data[j + 3] = bathymetry[k]
                j += 4
            }
        }
        return data
    }

}