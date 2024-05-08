package me.anno.tsunamis.image

import me.anno.image.raw.IFloatImage
import java.nio.FloatBuffer

class FloatBufferImage(width: Int, height: Int, channels: Int, val data: FloatBuffer) :
    IFloatImage(width, height, channels) {

    override fun getValue(index: Int, channel: Int): Float {
        return data[index * numChannels + channel]
    }

    override fun setValue(index: Int, channel: Int, value: Float): Float {
        val idx = index * numChannels + channel
        val prev = data[idx]
        data.put(idx, value)
        return prev
    }
}