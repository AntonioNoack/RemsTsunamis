package me.anno.tsunamis

enum class Visualisation(
    @Suppress("UNUSED")
    val id: Int
) {
    HEIGHT_MAP(0),
    MOMENTUM_X(1),
    MOMENTUM_Y(2),
    WATER_SURFACE(3)
}