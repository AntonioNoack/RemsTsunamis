package me.anno.tsunamis.engine

import me.anno.tsunamis.engine.gpu.ComputeEngine
import me.anno.tsunamis.engine.gpu.GraphicsEngine
import me.anno.tsunamis.engine.gpu.SharedMemoryEngine
import me.anno.tsunamis.engine.gpu.TwoPassesEngine

@Suppress("unused")
enum class EngineType(val id: Int) {
    CPU(0),
    GPU_GRAPHICS(1),
    GPU_COMPUTE(2),
    GPU_2PASSES(3),
    GPU_SHARED_MEMORY(4);

    fun create(w: Int, h: Int) = create(this, w, h)

    companion object {
        fun create(type: EngineType, w: Int, h: Int): TsunamiEngine {
            return when (type) {
                CPU -> CPUEngine(w, h)
                GPU_GRAPHICS -> GraphicsEngine(w, h)
                GPU_COMPUTE -> ComputeEngine(w, h)
                GPU_2PASSES -> TwoPassesEngine(w, h)
                GPU_SHARED_MEMORY -> SharedMemoryEngine(w, h)
            }
        }
    }
}