# Report

This is a report about what the extension implements, what caused issues, and what the performance results were.

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


## Drawing displacements

The extension allows the user to hand-draw tsunamis for testing. It is currently implemented as linear sections,
which are applied as kernel functions. The drawing direction defines in which direction the fluid will be higher, and in
which it will be lower.

Since 24.01.2022, these kernels work on all solver types.


## Dam Break Setups

Dambreak setups of different kinds are joined in the classes LinearDiscontinuity and CircularDiscontinuity.
In the C++ side, I added a rectangle, which can have different height, to test the capabilities of the solver.
In this extension, this extra rectangle hasn't been implemented.


## Critical Setups

Both sub-critical and super-critical setup types share a common mathematical formula, so they are implemented together in
CriticalFlowSetup. Additionally, the shape can be influenced using the "power" parameter, which was 2 (polynomial of 2nd degree) in
the C++ experiments.


## GMT Track Setup

The first tsunami type, that was explored in the Tsunami Lab module was an artificial sine-shaped tsunami on a one-dimensional track on
the coast near Tohoku towards the ocean, which was extracted using GMT.
The extensions can read these files as well, and uses the CSV Reader of Rem's Engine, that I originally added for tests for the "Big Data" module.


## Tsunami in a Pool

As a first tsunami test, a pool with artificial displacement was used in C++. This setup can be re-created using the PoolSetup class.


## Color Maps & File Previews

ParaView and GMT have a selection of color maps for visualization. My extension not only supports them for displaying the terrain mesh,
but also allows the user to preview them in Rem's Engine's file explorer. Most color map types work, but not all.

NetCDF files can be previewed as well. Scalar values are written to text files, while 1d and 2d values are displayed as an image.

The file types are defined in the class FluidSimMod using their file signature (first few bytes, which often are "magic" values).

## Running Rem's Engine on the ARA cluster

Java run fine from the beginning, but I had issues with LWJGL.
These were solved by opening the session with the SSH parameter -X and by using the "window"-libary EGL instead of GLFW.
Additionally, of course, the engine must run a node with a GPU. I run my tests on the group "gpu_test", and created jobs using "salloc --partition=gpu_test".

The GPU was detected as "Tesla P100-PCIE-16GB/PCIe/SSE2".

Before I got it running, I also tested on gpu01.inf-ra.uni-jena.de (RTX 2070 Super), gpu02 (GTX 780), gpu03 (GTX 780) via SSH sessions.

## First Measurements

While all measurements look rather slow, the GPU with compute is currently upto 1000x faster than Kotlin.

Then I noticed that I forgot to set the scene size, and the test was only running on a 10x10 field.

With the corrected test (field of 10800 x 6000), the results look reasonable, and GLSL on the GPU is about 50x faster than Kotlin on my CPU.

## Engine types

The extension abstracts the engine, which applies the cell updates to the field broadly. This allows different kinds of solvers to be switched on the fly within the extension.
Multiple engines were implemented:

### CPU Engine

The first engine is a Kotlin implementation of what was previously implemented using C++ and OpenMP. I uses multi-threading as well,
but typically uses one thread less than on the system available, because it is a game engine, and a thread must stay available for
the UI.

### GPU Graphics Engine

All following types, including this one, are implemented on the GPU. They are written in the shader language GLSL, and controlled using
OpenGL (in the library LWJGL).

The graphics engine computes the time steps separately. From the graphics pipeline, you only can write to a single pixel (storage cell) at once from a kernel.
Because of that, the shader computes the update from the left (top) and right (bottom) cell edge, and then writes the updated value to the result framebuffer (writable 2d memory within OpenGL).

### GPU Compute Engine

The compute pipeline of OpenGL allows for more advanced memory accesses, e.g. shared memory. It has the disadvantage from a graphics perspective, that you cannot use the hardware rasterizer and tesselator.

My first compute engine uses fundamentally the same principles as the graphics engine, just loading and storing uses functions with different names.
Behind the scenes, they probably are pretty much the same. In the graphics pipeline, the functions have more advanced features like mipmapping, interpolation, anisotropic filtering, blending and swizzle masks.
In the engine however, we use none of those.

### Two Passes Engine

As I have described, the previous GPU solvers computed all updates on the edges twice: two updates per cell.
The two-passes engine tries to optimize this by writing the updates to a texture, and reading them in a second pass.
This has the advantage of lower computational effort, but the disadvantage of more required memory bandwidth (e.g. the edge-updates to be stored to memory temporarily).

### YX Engine

All compute engines had way worse performance on the Tesla P100 than with the graphics pipeline. It might have been that the stride was suboptimal, so
I tried to change the addressing of the compute cells. The results were even worse than previously.

### Compute FP16 Engines

Since a modern processor is more likely to be bandwidth limited than compute limited, I also tried to implement solvers, which compute
in FP32, but store and load the data in FP16.
FP16 would not be enough to store detailed waves with a surface height of 5m in 5000m deep water, because they only have a 10 bit mantissa.
This problem was solved by storing the surface height instead of the full water depth.

I created two versions: one with FP16 and one with FP32 bathymetry.

## Performance Results & Comparisons

todo...

## Graphics Pipeline VS Compute Pipeline - Ease of implementation

Since on my GPU there is no performance difference, and on the Tesla P100, the graphics pipeline currently is twice as fast, 
it's hard to generally use the compute pipeline.

The main core is thanks to GLSL the same, so only loading, storing, and where the computation takes place differs.
The compute pipeline offers more advanced features, but they may not be needed in every scenario.
