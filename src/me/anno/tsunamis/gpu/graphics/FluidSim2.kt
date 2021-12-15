package me.anno.tsunamis.gpu.graphics

import me.anno.gpu.shader.Shader

class FluidSim2 {

    val applyXStep by lazy {
        // todo compute update on x axis
        Shader("applyXStep",null, "", listOf(), "" +
                "uniform sampler2D state0;\n" +
                "void main(){" +
                "   vec4 s0 = texelFetch(state0, uv, 0);" +
                "   vec4 s1 = texelFetch(state0, uv+vec2(1,0), 0);" +
                "   " +
                "}")
    }

    fun step() {

    }

}