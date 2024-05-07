package me.anno.tsunamis.image

import me.anno.image.raw.IFloatImage

class CompositeFloatImage(width: Int, height: Int, val channels: Array<FloatArray>) :
    IFloatImage(width, height, channels.size) {

    override fun getValue(index: Int, channel: Int): Float {
        return channels[channel][index]
    }

    override fun normalize(): IFloatImage {
        TODO("Not yet implemented")
    }

    override fun reinhard(): IFloatImage {
        TODO("Not yet implemented")
    }

    override fun setValue(index: Int, channel: Int, value: Float): Float {
        val data = channels[channel]
        val prev = data[index]
        data[index] = value
        return prev
    }

}