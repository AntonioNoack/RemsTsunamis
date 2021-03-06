# Project Plan

## Integration into Rem's Engine
*until 10.01.2022, CPU compute*

- [x] create extension
- [x] add tsunami component as a ProceduralMesh
- [x] port setups from C++ to engine as components
- [x] make them work with the editor
- [x] mesh generation

- [x] color map import
- [x] different visualizations (surface height, momentum in x/y direction, wave height)

To save bandwidth, we could use a static, or even on-gpu-procedurally generated grid, where the height is read from a texture.
- [x] use custom vertex shader, and texture for height? (engine would need to be adjusted, as this isn't currently
  supported)

Others:

- [x] draw force/impulse in a certain area
- [x] restart button

- [x] it would be interesting to have different resolutions depending on the distance to the camera
  (levels of detail / output coarsening)

### NetCDF Support
*until 10.01.2022*

- [x] NetCDF import
- [x] NetCDF export, if we want to use ParaView/Visit for visualization

- [x] define NetCDF as image type for preview purposes

## GLSL vs Compute
### Basics
*17.01.2022*

- [x] write graphics shaders
- [x] write compute shaders

They probably will share the core components

- [x] outflow boundary condition can be done using clamping (computed in graphics shaders anyways)

- [x] measure baseline performance & bandwidth
- [x] compare with theoretical hardware capabilities

- [x] take a look at AMD GPUOpen developer tools

### Performance Improvements
*until 24.01.2022*

#### Theoretical

I've found that the atomic operations in OpenGL (compute) are too limited for our purposes.
They only support integers, not floating point values.
I don't know whether barriers will solve the problem of writing to a cell, reading its value, and then writing again.

#### Solutions, if it doesn't work

Each cell has influence on its two neighbors Possibilities:
- [x] two passes, one for left neighbor, one for right one 2x computation needed doubled number of passes
- [x] just compute both in a single pass 2x computation needed 
- [x] shared memory within a compute group: within this groups, the results can be shared only ~1.05x computations needed
<!-- - [ ] compute with integers instead of floats; probably would be complicated, error prone, and maybe would bring nothing -->

#### Other optimizations

- [x] Disable mipmaps, currently they are always created after a texture has changed (mipmaps are used for rendering with less aliasing)
<!-- - [ ] It could be tested whether writing formulas as floats or with vectors (vec2) makes a difference. -->
- [ ] If there is enough time, Pixel Buffer Objects could be used for optimized, asynchronous data transfer.

Another task:

- [x] compare the ease of implementation between graphics and compute pipeline

### Performance Measurements & Towards HPC
*test this until 24.01.2022*

- [x] get java running / test it on ARA cluster or GPU node
- [x] get engine running on ARA cluster or GPU node -> will LWJGL work?
- [x] configuration options with YAML files

- [x] print the actual simulation speed

### Measurements
*until 30.01.2022 (day before final presentation)*

*presentation maybe 02.02. (wednesday), 10:00*

- [x] measure performance
- [x] measure bandwidth
- [x] compare them to the CPU implementation in C++
- [x] integrate the "best" solution into the FluidSim component for real time visualization