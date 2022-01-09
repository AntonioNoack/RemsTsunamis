package me.anno.tsunamis.io

/**
 * unfortunately, the NetCDF library seems not to be thread-safe :/,
 * so we need to serialize all access to it
 *
 * the reason seams to be the HDF5 library, which itself seems to have a few global variables
 * */
object NetCDFMutex