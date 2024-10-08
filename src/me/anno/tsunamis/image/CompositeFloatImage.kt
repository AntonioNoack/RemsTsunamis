package me.anno.tsunamis.image

import me.anno.image.colormap.LinearColorMap
import me.anno.image.raw.IFloatImage

class CompositeFloatImage(width: Int, height: Int, private val channels: Array<FloatArray>) :
    IFloatImage(width, height, channels.size, LinearColorMap.default) {

    override fun getValue(index: Int, channel: Int): Float {
        return channels[channel][index]
    }

    override fun setValue(index: Int, channel: Int, value: Float): Float {
        val data = channels[channel]
        val prev = data[index]
        data[index] = value
        return prev
    }
}