package me.anno.tsunamis.engine.gpu

import me.anno.tsunamis.FluidSim
import me.anno.tsunamis.engine.CPUEngine
import me.anno.tsunamis.engine.TsunamiEngine

object GLSLSolver {

    /**
     * texture memory layout: height, momentum x, momentum y, bathymetry
     * ghost outflow is handled by clamping coordinates
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
            "       hu.x = -hu.y;\n" + // a flop?
            "   } else if(h.y <= 0.0){\n" +
            "       h.y  = h.x;\n" +
            "       b.y  = b.x;\n" +
            "       hu.y = -hu.x;\n" +
            "   }\n" +
            "   float roeHeight = (h.x + h.y) * 0.5;\n" + // 2 flops
            "   vec2 sqrt01 = sqrt(h);\n" + // 1 flop
            // "   vec2 u = vec2(h.x > 0.0 ? hu.x / h.x : 0.0, h.y > 0.0 ? hu.y / h.y : 0.0);\n" +
            "   vec2 u = hu / h;\n" + // 1 flop
            "   float roeVelocity = (u.x * sqrt01.x + u.y * sqrt01.y) / (sqrt01.x + sqrt01.y);\n" + // 5 flops
            "   float gravityTerm = sqrt(gravity * roeHeight);\n" + // 2 flops
            "   vec2 lambda = vec2(roeVelocity - gravityTerm, roeVelocity + gravityTerm);\n" + // 2 flops
            "   float invLambda = 0.5 / gravityTerm;\n" + // 1 flop
            "   float bathymetryTerm = gravity * roeHeight * (b.y - b.x);\n" + // 3 flops
            "   float df0 = hu.y - hu.x;" + // 1 flop
            "   float df1 = hu.y * u.y - hu.x * u.x" + // 3 flops
            "                   + gravity * 0.5 * (h.y * h.y - h.x * h.x)" + // 6 flops
            "                   + bathymetryTerm;\n" + // 1 flop
            "   vec2 deltaH  = invLambda * vec2(df0 * lambda.y - df1, df1 - df0 * lambda.x);\n" + // 6 flops
            "   vec2 deltaHu = deltaH * lambda;\n" // 2 flops, total: 39 flops

    const val fWaveSolverHalf = "" +
            "vec2 solveXY(vec3 data0, vec3 data1){\n" +
            fWaveSolverParams +
            "   if(h.x <= 0.0 || h.y <= 0.0) return vec2(0);\n" + // on land
            fWaveSolverBase + // 39 flops
            "   vec2 dst = vec2(0.0);\n" +
            "   if(lambda.x < 0.0){\n" +
            "       dst.x  = deltaH.x;\n" +
            "       dst.y  = deltaHu.x;\n" +
            "   }\n" +
            "   if(lambda.y < 0.0){\n" +
            "       dst.x += deltaH.y;\n" + // 1 flop
            "       dst.y += deltaHu.y;\n" + // 1 flop
            "   }\n" +
            "   return dst;\n" + // total: 41 flops
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

    // todo why is it not working inside 1 call???
    const val fWaveSolverFull = fWaveSolverHalf + "vec4 solve(vec3 data0, vec3 data1){\n" +
            "   return vec4(solveXY(data0, data1), solveZW(data0, data1));\n" +
            "}\n" // total: 41 flops

    fun createTextureData(
        w: Int, h: Int,
        engine: CPUEngine,
        data: FloatArray
    ): FloatArray {
        return createTextureData(
            w, h,
            engine.fluidHeight,
            engine.fluidMomentumX,
            engine.fluidMomentumY,
            engine.bathymetry,
            data
        )
    }

    fun createTextureData(
        w: Int, h: Int,
        fluidHeight: FloatArray,
        momentumX: FloatArray,
        momentumY: FloatArray,
        bathymetry: FloatArray,
        data: FloatArray
    ): FloatArray {
        if (data.size != w * h * 4) throw IllegalArgumentException()
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

    /**
     * @param w width with ghost cells
     * @param h height with ghost cells
     * @param cw coarse width with ghost cells
     * @param ch coarse height with ghost cells
     * @return data of the inner, coarsened field
     * */
    fun createTextureData(
        w: Int, h: Int,
        cw: Int, ch: Int,
        engine: CPUEngine,
        dst: FloatArray
    ): FloatArray {
        return createTextureData(
            w, h, cw, ch,
            engine.fluidHeight,
            engine.fluidMomentumX,
            engine.fluidMomentumY,
            engine.bathymetry,
            dst
        )
    }

    /**
     * struct of arrays -> array of structs,
     * with ghost cells -> without ghost cells
     * @param w width with ghost cells
     * @param h height with ghost cells
     * @param cw coarse width with ghost cells
     * @param ch coarse height with ghost cells
     * @return data of the inner, coarsened field
     * */
    fun createTextureData(
        w: Int, h: Int,
        cw: Int, ch: Int,
        fluidHeight: FloatArray,
        fluidMomentumX: FloatArray,
        fluidMomentumY: FloatArray,
        bathymetry: FloatArray,
        dst: FloatArray
    ): FloatArray {
        val destSize = (cw - 2) * (ch - 2) * 4
        if (dst.size < destSize) {
            throw RuntimeException("Destination buffer not large enough, ${dst.size} < $destSize = ($cw-2)*($ch-2)*4")
        }
        val sourceSize = w * h
        if (fluidHeight.size < sourceSize ||
            fluidMomentumX.size < sourceSize ||
            fluidMomentumY.size < sourceSize ||
            bathymetry.size < sourceSize
        ) {
            throw IndexOutOfBoundsException(
                "Not enough data! $w x $h needs $sourceSize cells, " +
                        "got (" +
                        "${fluidHeight.size}, " +
                        "${fluidMomentumX.size}, " +
                        "${fluidMomentumY.size}, " +
                        "${bathymetry.size})"
            )
        }
        if(cw == w && ch == h){
            var j = 0
            for (y in 1 until ch - 1) {
                var index = 1 + y * cw
                for (x in 1 until cw - 1) {
                    dst[j++] = fluidHeight[index]
                    dst[j++] = fluidMomentumX[index]
                    dst[j++] = fluidMomentumY[index]
                    dst[j++] = bathymetry[index]
                    index++
                }
            }
        } else {
            var j = 0
            for (y in 1 until ch - 1) {
                var coarseIndex = 1 + y * cw
                for (x in 1 until cw - 1) {
                    val fineIndex = FluidSim.coarseIndexToFine(w, h, cw, ch, coarseIndex)
                    dst[j++] = fluidHeight[fineIndex]
                    dst[j++] = fluidMomentumX[fineIndex]
                    dst[j++] = fluidMomentumY[fineIndex]
                    dst[j++] = bathymetry[fineIndex]
                    coarseIndex++
                }
            }
        }
        return dst
    }

}