
# Project Plan

## Integration into Rem's Engine (until 10.01.2022)

- create extension
- add tsunami component
- port setups from C++ to engine
- make them work with the editor
- mesh generation

- color map import
- use custom vertex shader, and texture for height?

- draw force/impulse in a certain area
- restart button

- it would be interesting to have different resolutions depending on the distance to the camera
(levels of detail)

### NetCDF (until 10.01.2022 as import is already working)

- NetCDF import
- NetCDF export

- define NetCDF as image type for preview purposes
(this already works)

## GLSL vs Compute

### Basics (until 17.01.2022)

- write graphics & compute shaders
- they probably will share the core components
- outflow boundary condition can be done using clamping (computed in graphics shaders anyways)

- measure performance & bandwidth
- compare with theoretical hardware capabilities

### Performance Improvements (until 24.01.2022)

Each cell has influence on its two neighbors
Possibilities:
a) two passes, one for left neighbor, one for right one
	2x computation needed
	doubled number of passes
b) just compute both in a single pass
	2x computation needed
c) shared memory within a compute group: within this groups, the results can be shared
	only ~1.05x computations needed
d) compute with integers instead of floats; probably would be complicated, error prone,
	and maybe would bring nothing
	
- disable mipmaps, currently they are always created after a texture has changed
(mipmaps are used for rendering with less aliasing)

### Performance Measurements & Towards HPC, test this until 24.01.2022

- get java running / test it on ARA cluster or GPU node
- get engine running on ARA cluster or GPU node -> will LWJGL work?

If not,
a) test on another computer
b) use LWJGL via different paths
c) use Vulkan (will be complicated, and surely take a week)
d) use CUDA + C/C++ instead

### Measurements until 31.01.2022

- measure performance
- measure bandwidth

If OpenGL + Java + LWJGL works,
- use pixel buffer objects for optimized, async data transfer?