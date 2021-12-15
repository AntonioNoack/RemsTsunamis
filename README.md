# Tsunami Extension for Rem's Engine

My project for the Tsunami Lab is split into two parts, which are developed together.
On the one hand, the solver and simulation parts will be integrated into
[my game engine "Rem's Engine"](https://github.com/AntonioNoack/RemsStudio/blob/master/RemsEngine.md) as an extension.
Rem's Engine is a Kotlin<sup id="a1">[1](#f1)</sup>, Entity-Component based engine, which is built on top of OpenGL (using [LWJGL](https://www.lwjgl.org/)).
The goal is to visualize and simulate smaller tsunami simulations in real time using OpenGL.
This will allow the user to change and investigate setup and simulation parameters directly.

On the other hand, the solver currently only works on the CPU and the project will investigate potential performance 
improvements by using the GPU. There are two straightforward ways, which can be relatively easily used with OpenGL:
[graphics shaders](https://www.khronos.org/opengl/wiki/Shader) and [compute shaders](https://www.khronos.org/opengl/wiki/Compute_Shader). The project will investigate which one is easier to implement,
and how performance differs.

Graphics shaders offer the advantage of broader hardware support, because they have been around for longer,
while compute shaders offer more flexible memory accesses<sup id="a2">[2](#f2)</sup>, shared memory and atomic operations.

The testing hardware for this project will be my desktop computer (Ryzen 5 2600 + Radeon RX 580 8GB),
and optionally my laptop (Ryzen 7 4700U + integrated graphics).

<b id="f1">1</b> Kotlin is a high level language with different compiler targets.
The target for Rem's Engine is Java Virtual Machine. [↩](#a1)<br>
<b id="f2">2</b> Using the graphics pipeline, you can only write in the shape of triangles, lines or points.
Writing to arbitrary memory locations directly is not possible from the fragment shader. [↩](#a2)