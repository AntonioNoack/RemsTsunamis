# Tsunami Extension for Rem's Engine

My project for the Tsunami Lab is split into two parts, which are developed together.
On the one hand, I want to integrate the solver and simulation parts into my game engine "Rem's Engine".
It is a Kotlin (JVM), Entity-Component based engine, which is built on top of OpenGL.
The goal is to visualize and simulate smaller tsunami simulations in real time using OpenGL.
This will allow the user to change and investigate setup and simulation parameters directly.

On the other hand, the solver currently works on the CPU and the project shall investigate potential performance 
improvements by using the GPU. There are two straightforward "methods", which can be relatively easily used in Rem's Engine/OpenGL:
graphics shaders and compute shaders. The project will investigate which one is easier to implement,
and what the performance differences are.

As my testing hardware, I will use my desktop computer (Ryzen 5 2600 + RX 580 8GB),
and optionally my laptop (Ryzen 7 4700U + integrated graphics).