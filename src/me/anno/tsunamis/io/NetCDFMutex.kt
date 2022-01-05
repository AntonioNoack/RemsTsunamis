package me.anno.tsunamis.io

/**
 * unfortunately, the NetCDF library seems not to be thread-safe :/,
 * so we need to serialize all access to it
 * */
object NetCDFMutex