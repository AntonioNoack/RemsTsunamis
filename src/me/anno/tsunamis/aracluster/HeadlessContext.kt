package me.anno.tsunamis.aracluster

import me.anno.gpu.GFX
import org.apache.logging.log4j.LogManager
import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL14.*
import org.lwjgl.egl.EXTDeviceBase.eglQueryDevicesEXT
import org.lwjgl.egl.EXTPlatformBase.eglGetPlatformDisplayEXT
import org.lwjgl.egl.EXTPlatformDevice.EGL_PLATFORM_DEVICE_EXT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*

object HeadlessContext {

    private val LOGGER = LogManager.getLogger(HeadlessContext::class)

    private var display = 0L

    private fun check() {
        val error = eglGetError()
        if (error != 0 && error != EGL_SUCCESS) {
            val errorName = when (error) {
                EGL_BAD_ACCESS -> "bad access"
                EGL_BAD_ALLOC -> "bad alloc"
                EGL_BAD_ATTRIBUTE -> "bad attribute"
                EGL_BAD_CONFIG -> "bad config"
                EGL_BAD_CONTEXT -> "bad context"
                EGL_BAD_CURRENT_SURFACE -> "bad current surface"
                EGL_BAD_DISPLAY -> "bad display"
                EGL_BAD_MATCH -> "bad match"
                EGL_BAD_NATIVE_PIXMAP -> "bad native pixmap"
                EGL_BAD_NATIVE_WINDOW -> "bad native window"
                EGL_BAD_PARAMETER -> "bad parameter"
                EGL_BAD_SURFACE -> "bad surface"
                else -> "$error"
            }
            LOGGER.warn("Got EGL error $errorName")
        }
    }

    // https://github.com/NVIDIA-developer-blog/code-samples/blob/master/posts/egl_OpenGl_without_Xserver/tinyegl.cc
    /**
     * creates an OpenGL context, which does not require an X11 server
     * */
    fun createContext(width: Int = 512, height: Int = width, useDefaultDisplay: Boolean) {

        check()

        val eglExtensions = eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS)
        LOGGER.debug("EGL extensions: $eglExtensions")

        check()

        if (useDefaultDisplay) {
            // gpu02: works, GTX 780
            // login.fmi: EGL init failed
            // ara gpu_test: EGL init failed
            display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
            check()
        } else {
            // login.fmi.uni-jena.de: failed to get EGL display
            // ara gpu_test: no EGL devices found
            val maxDevices = 16
            val devices = PointerBuffer.allocateDirect(maxDevices)
            val numDevices = IntArray(1)
            eglQueryDevicesEXT(devices, numDevices)
            check()
            if (numDevices[0] == 0) throw RuntimeException("No EGL devices found")
            LOGGER.info("Detected ${numDevices[0]} devices: ${(0 until numDevices[0]).map { devices[it] }}")
            display = eglGetPlatformDisplayEXT(EGL_PLATFORM_DEVICE_EXT, devices[0], null as IntArray?)
            check()
        }

        if (display == EGL_NO_DISPLAY) {
            throw RuntimeException("Failed to get EGL display")
        }

        LOGGER.info("EGL default display: $display")

        val configAttributes = intArrayOf(
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_DEPTH_SIZE, 8,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_NONE
        )

        val pbufferAttributes = intArrayOf(
            EGL_WIDTH, width,
            EGL_HEIGHT, height,
            EGL_NONE
        )

        val majorPtr = IntArray(1)
        val minorPtr = IntArray(1)
        if (!eglInitialize(display, majorPtr, minorPtr)) {
            throw RuntimeException("EGL init failed")
        }
        check()

        val major = majorPtr[0]
        val minor = minorPtr[0]

        LOGGER.info("Inited EGL context of version $major.$minor")

        val numConfigs = IntArray(1)
        val configPtr = PointerBuffer.allocateDirect(1)
        eglChooseConfig(display, configAttributes, configPtr, numConfigs)
        check()

        val config = configPtr[0]
        eglBindAPI(EGL_OPENGL_API)
        check()

        val context = EGL10.eglCreateContext(display, config, EGL_NO_CONTEXT, null as IntArray?)
        check()

        val surf = EGL10.eglCreatePbufferSurface(display, config, pbufferAttributes)
        eglMakeCurrent(display, surf, surf, context)
        check()

        GL.createCapabilities()

        GFX.glThread = Thread.currentThread()

        GFX.check()

        LOGGER.info("Running OpenGL/EGL on ${"${glGetString(GL_VENDOR)} ${glGetString(GL_RENDERER)}".trim()}")

    }

    fun destroyContext() {
        eglTerminate(display)
    }

}