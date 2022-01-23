# Report

## Creating an extension

To create the extension was [relatively simple](https://github.com/AntonioNoack/RemsStudio/wiki/Creating-Custom-Extensions), and to translate the existing C++ code to Java as well.
If I were to use native libraries, I would have to compile them for all target architectures (Windows x64, Windows x86, Windows ARM, Linux ARM32, Linux ARM64, MacOS, maybe Android) of the engine.

A first hindrance of the project was that sometimes, the NetCDF data failed to load, and it crashed somewhere.
The solution was that Rem's Engine is parallelized in a lot of places, and the Java library for NetCDF is not thread-safe.
So I created a mutex, and always lock it, when I need to call the library.

When I created the mode to draw waves in real-time, I had the issue that it was extremely laggy, and the engine froze when the user was drawing.
The cause of that issue was that mouse-motion events were used, and they are captured & processed, no matter how many per frame.
Before an update was completely processed, the next event would be registered for processing.
The solution to that was to add a timeout to applying steps.

## ...

Fixed boundary condition bug, fixed drawing code :)

Defined "engine/solver types", so how and where the simulation is run, e.g. cpu / gpu graphics pipeline

Fixed NetCDF loading code: off by 1 error

Created performance test

Created UI panel for color map preview

## First Measurements

While all measurements look rather slow, the GPU with compute is currently upto 1000x faster than Kotlin.

Then I noticed that I forgot to set the scene size, and the test was only running on a 10x10 field.

With the corrected test (field of 10800 x 6000), the results look reasonable, and GLSL on the GPU is about 50x faster than Kotlin on my CPU.

## ARA cluster

Running Rem's Engine on the cluster:

If it does not work,
- test on another computer
- use LWJGL via different paths
- use Vulkan (will be complicated, and surely take a week)
- use CUDA + C/C++ instead

#### Updates:

I've done some first tests:
- Java runs fine
- LWJGL can call functions, but OpenGL is needed

OpenGL needs, as far as I know, a window context of some sort.
GLFW is the window library, that I usually use in Rem's Engine.
EGL is another one, which is kind-of said to work without X11.

- GLFW does not run, as it fails to create a window without X11
- EGL may have a work-around, but it hasn't worked for me yet on ARA/gpu_test

It works on gpu01.inf-ra.uni-jena.de (RTX 2070 Super), gpu02 (GTX 780), gpu03 (GTX 780).
It fails on ARA/gpu_test and login.fmi.uni-jena.de.

#### Solution:

Creating a job with salloc, and logging in with the option "-X" solved the issue. A driver was found,
and the "Tesla P100-PCIE-16GB/PCIe/SSE2" GPU was correctly detected.

## Graphics Pipeline VS Compute Pipeline - Ease of implementation

Since on my GPU there is no performance difference, and on the Tesla P100, the graphics pipeline currently is twice as fast, 
it's hard to generally use the compute pipeline.

The main core is thanks to GLSL the same, so only loading, storing, and where the computation takes place differs.
The compute pipeline offers more advanced features, but they may not be needed in every scenario.
