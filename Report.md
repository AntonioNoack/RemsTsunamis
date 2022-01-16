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