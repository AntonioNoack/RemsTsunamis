package me.anno.tsunamis.setups

object ShoreLine {

    fun toBathymetry(bathymetry: Float, shoreCliffHeight: Float): Float {
        val shoreMin = -shoreCliffHeight
        return when {
            bathymetry <= shoreMin || bathymetry >= shoreCliffHeight -> bathymetry
            bathymetry < 0f -> shoreMin
            else -> shoreCliffHeight
        }
    }

    fun toFluidHeight(bathymetry: Float, shoreMax: Float): Float {
        val shoreMin = -shoreMax
        return when {
            bathymetry >= 0f -> 0f // beach / land
            bathymetry > shoreMin -> shoreMax // cliff zone
            else -> -bathymetry // ocean
        }
    }

}