package me.anno.tsunamis.io

import me.anno.language.translation.NameDesc
import me.anno.ui.editor.color.ColorSpace
import org.joml.Vector3f

object RGBColorSpace : ColorSpace(
    NameDesc("RGB"), lazy { "vec3 spaceToRGB(vec3 rgb){ return rgb; }\n" },
    Vector3f(0f, 0f, 0f)
) {
    override fun fromRGB(rgb: Vector3f, dst: Vector3f): Vector3f {
        return dst.set(rgb)
    }

    override fun toRGB(input: Vector3f, dst: Vector3f): Vector3f {
        return dst.set(input)
    }
}