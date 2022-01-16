package me.anno.tsunamis.engine

@Suppress("unused")
enum class EngineType(val id: Int) {
    CPU(0),
    GPU_GRAPHICS(1),
    GPU_COMPUTE(2)
}