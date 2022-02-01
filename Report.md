# Report

This is a report about what the extension implements and what the performance results were.

## Creating an extension

To create the extension was [relatively simple](https://github.com/AntonioNoack/RemsStudio/wiki/Creating-Custom-Extensions), and to translate the existing C++ code to Java as well.
If I were to use native libraries, I would have to compile them for all target architectures (Windows x64, Windows x86, Windows ARM, Linux ARM32, Linux ARM64, MacOS, maybe Android) of the engine.


## Drawing displacements

The extension allows the user to hand-draw tsunamis for testing. It is currently implemented as linear sections,
which are applied as kernel functions. The drawing direction defines in which direction the fluid will be higher, and in
which it will be lower.

Since 24.01.2022, these kernels work on all solver/engine types.


## Setups

Just like C++, I implemented multiple setup types for the simulations.


### Dam Break Setups

Dambreak setups of different kinds are joined in the classes LinearDiscontinuity and CircularDiscontinuity.
In the C++ side, I added a rectangle, which can have different height, to test the capabilities of the solver.
In this extension, this extra rectangle hasn't been implemented.


### Critical Setups

Both sub-critical and super-critical setup types share a common mathematical formula, so they are implemented together in
CriticalFlowSetup. Additionally, the shape can be influenced using the "power" parameter, which was 2 (polynomial of 2nd degree) in
the C++ experiments.


### GMT Track Setup

The first tsunami type, that was explored in the Tsunami Lab module was an artificial sine-shaped tsunami.
It was a one-dimensional track from the coast near Tohoku towards the ocean, and extracted using GMT.
The extensions can read these files as well, and uses the CSV Reader of Rem's Engine, that I originally added for tests for the "Big Data" module.


### Tsunami in a Pool

As a first tsunami test, a pool with artificial displacement was used in C++. This setup can be re-created using the PoolSetup class.


### NetCDF Setups

To load NetCDF files, I used the [official NetCDF library](https://www.unidata.ucar.edu/software/netcdf-java/).
I load them asynchronously to the main thread, so they don't cause too big lag spikes.
The actual raw data is stored within the class VariableImage, which is then used by the setup class NetCDFSetup.

Additionally to reading NetCDF files, they also can be exported from the editor of the game engine (there is a button for it).


## Color Maps & File Previews

ParaView and GMT have a selection of color maps for visualization. My extension not only supports them for displaying the terrain mesh,
but also allows the user to preview them in Rem's Engine's file explorer. Most color map types work, but not all.

NetCDF files can be previewed as well. Scalar values are written to text files, while 1d and 2d values are displayed as an image.

The file types are defined in the class FluidSimMod using their file signature (first few bytes, which often are "magic" values).


## YAML configurations

Within the engine, YAML configurations cannot be used, because the engine uses its own Entity-Component system, and saves file in
a custom JSON file format with pointers.

However, you can use YAML configuration files like on the C++ side to define a scenario to be simulated.
This scenario can be either performance-measured using Performance.kt or a video can be created using the VideoRenderer object.


## Running Rem's Engine on the ARA cluster

Java run fine from the beginning, but I had issues with LWJGL.
These were solved by opening the session with the SSH parameter -X and by using the "window"-libary EGL instead of GLFW.
Additionally, of course, the engine must run a node with a GPU. I run my tests on the group "gpu_test", and created jobs using "salloc --partition=gpu_test".

The GPU was detected as "Tesla P100-PCIE-16GB/PCIe/SSE2".

Before I got it running, I also tested on gpu01.inf-ra.uni-jena.de (RTX 2070 Super), gpu02 (GTX 780), gpu03 (GTX 780) via SSH sessions.


## Engine types

The extension abstracts the engine, which applies the cell updates to the field broadly. This allows different kinds of solvers to be switched on the fly within the extension.
Multiple engines were implemented:


### CPU Engine

The first engine is a Kotlin implementation of what was previously implemented using C++ and OpenMP. It uses multi-threading as well,
but typically uses one thread less than on the system available, because it is a game engine, and a thread must stay available for
the UI.

### GPU Graphics Engine

All following types, including this one, are implemented on the GPU. They are written in the shader language GLSL, and controlled using
OpenGL (in the library LWJGL). The major advantage of a GPU engine for the extension is that visualization doesn't need a lot of CPU-GPU communication anymore.
However, it comes with its own disadvantage: when you want to know the position at a location, e.g., for stations or game mechanics, you need
this communication again.

From the graphics pipeline, you only can write to a single pixel (storage cell) at once from a kernel.
Because of that, the shader computes the update from the left (top) and right (bottom) cell edge, and then writes the updated value to the result framebuffer (writable 2d memory within OpenGL).

To visualize the CPU engines, the underlying triangle mesh is updated after every update/frame. For the GPU engines, 
the mesh is generated on the GPU in the Vertex shader stage of the graphics pipeline. This has the advantage of reduced CPU-GPU communication,
but also the issue that click-testing within the engine is currently done on the CPU side, so the fluid cannot be clicked directly, or the engine would need to fetch the data from the GPU for that case.

### GPU Compute Engine

The compute pipeline of OpenGL allows for more advanced memory accesses, e.g., shared memory. It has the disadvantage from a graphics perspective, that you cannot use the hardware rasterizer and tesselator.

My first compute engine uses fundamentally the same principles as the graphics engine, just loading and storing uses functions with different names.
Behind the scenes, they probably are pretty much the same. In the graphics pipeline, the functions have more advanced features like mipmapping, interpolation, anisotropic filtering, blending and swizzle masks.
In the engine however, we use none of those.


### Two Passes Engine (Compute)

As I have described, the previous GPU solvers computed all updates on the edges twice: two updates per cell.
The two-passes engine tries to optimize this by writing the updates to a texture, and reading them in a second pass.
This has the advantage of lower computational effort, but the disadvantage of more required memory bandwidth (e.g. the edge-updates to be stored to memory temporarily).


### Shared Memory Engine (Compute)

This additional bandwidth from the two-passes-solver can be removed by splitting the kernel itself with a group-wide barrier.
Each 16x16 (workers) group computes 16x16 edge updates, and therefore can compute the values of 16x15 (or 15x16) cells.


### YX Engine (Compute)

All compute engines had way worse performance on the Tesla P100 than with the graphics pipeline. It might have been that the stride was suboptimal, so
I tried to change the addressing of the compute cells. The results were even worse than previously.


### Compute FP16 Engines (Compute)

Since a modern processor is more likely to be bandwidth limited than compute limited, I also tried to implement solvers, which compute
in FP32, but store and load the data in FP16.
FP16 would not be enough to store detailed waves with a surface height of 5m in 5000m deep water, because they only have a 10 bit mantissa.
This problem was solved by storing the surface height instead of the full water depth.

I created two versions: one with FP16 and one with FP32 bathymetry.


## Performance Results

The field for the different processors mean how much time a full time step on a 10800 x 6000 field required. The lower, the better.

| Engine Type           | RX 580 8 GB | Ryzen 5 2600 | Tesla P100 16 GB | 2x Xeon Gold 6140 |
|-----------------------|------------:|-------------:|-----------------:|------------------:|
| CPU / C++             |           - |       0.31 s |                - |           0.067 s |
| CPU / Kotlin          |           - |       0.62 s |                - |           0.252 s |
| Graphics              |    0.0246 s |            - |         0.0052 s |                 - |
| Compute Default       |    0.0230 s |            - |         0.0105 s |                 - |
| Two Passes            |    0.0546 s |            - |         0.0214 s |                 - |
| Shared Memory         |    0.0236 s |            - |         0.0099 s |                 - |
| YX Memory Accesses    |    0.0331 s |            - |         0.1628 s |                 - |
| FP16, FP32 Bathymetry |    0.0117 s |            - |         0.0092 s |                 - |
| FP16, FP16 Bathymetry |    0.0110 s |            - |         0.0090 s |                 - |

The following bandwidth numbers are not what is theoretically needed, but what the machine uses internally. This allows for
a guess on "hardware utilisation". It uses the same 10800 x 6000 field.

It also assumes, that the GLSL code is executed as-is, without optimizations.
I additionally assume that each pixel is only loaded once from a texture,
so if a kernel run requests multiple pixels from a texture, I assume that the neighbor pixels were stored in caches.

| Engine Type           | RX 580 8 GB | Ryzen 5 2600 | Tesla P100 16 GB | 2x Xeon Gold 6140 |
|-----------------------|------------:|-------------:|-----------------:|------------------:|
| CPU / C++             |           - |   13.42 GB/s |                - |        62.09 GB/s |
| CPU / Kotlin          |           - |    6.71 GB/s |                - |        16.51 GB/s |
| Graphics              | 168.79 GB/s |            - |      803.04 GB/s |                 - |
| Compute Default       | 180.67 GB/s |            - |      395.92 GB/s |                 - |
| Two Passes            | 189.82 GB/s |            - |      484.08 GB/s |                 - |
| Shared Memory         | 175.94 GB/s |            - |      419.25 GB/s |                 - |
| YX Memory Accesses    | 125.20 GB/s |            - |       25.48 GB/s |                 - |
| FP16, FP32 Bathymetry | 155.32 GB/s |            - |      198.28 GB/s |                 - |
| FP16, FP16 Bathymetry | 118.26 GB/s |            - |      144.50 GB/s |                 - |

For the Tesla P100 on the graphics pipeline,
there must be some optimizations, because the calculated theoretical maximum bandwidth is 732 GB/s.
My shader supposedly loads and stores up to 803 GB/s using the Graphics pipeline.
A bandwidth optimization would possible, because the shader uses fewer data (h, hu, b) than what is actually requested (h, hu, hv, b).
This reduces the probable, actual bandwidth to ~700 GB/s, because only three input components are used, four are written.


For my RX 580 this means that it was probably memory-bandwidth limited.
The Tesla P100 had unknown issues with the Compute pipeline.

The best performance was achieved on the P100 using the graphics pipeline.
It was 12x faster than the dual 18 core Xeon Gold processors.

## Graphics Pipeline VS Compute Pipeline - Ease of implementation

Since on my GPU there is no performance difference, and on the Tesla P100, the graphics pipeline currently is twice as fast, 
it's hard to generally use the compute pipeline.

The main core is thanks to GLSL the same, so only loading, storing, and where the computation takes place differs.
The compute pipeline offers more advanced features, but they may not be needed in every scenario.

