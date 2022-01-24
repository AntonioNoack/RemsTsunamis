package me.anno.tsunamis.engine

import me.anno.tsunamis.engine.gpu.*

@Suppress("unused")
enum class EngineType(val id: Int) {
    CPU(0),
    GPU_GRAPHICS(1),
    GPU_COMPUTE(2),
    GPU_2PASSES(3),
    GPU_SHARED_MEMORY(4),
    GPU_COMPUTE_YX(5),
    GPU_COMPUTE_FP16B32(6),
    GPU_COMPUTE_FP16B16(7);

    fun create(w: Int, h: Int) = create(this, w, h)

    companion object {
        fun create(type: EngineType, w: Int, h: Int): TsunamiEngine {
            return when (type) {
                CPU -> CPUEngine(w, h)
                GPU_GRAPHICS -> GraphicsEngine(w, h)
                GPU_COMPUTE -> ComputeEngine(w, h)
                GPU_2PASSES -> TwoPassesEngine(w, h)
                GPU_SHARED_MEMORY -> SharedMemoryEngine(w, h)
                GPU_COMPUTE_YX -> ComputeYXEngine(w, h)
                GPU_COMPUTE_FP16B32 -> Compute16B32Engine(w, h)
                GPU_COMPUTE_FP16B16 -> Compute16B16Engine(w, h)
            }
        }
    }
}