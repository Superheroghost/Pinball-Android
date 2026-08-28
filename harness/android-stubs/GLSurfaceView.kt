package android.opengl

import android.content.Context
import android.view.View
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Harness-only stand-in for android.opengl.GLSurfaceView. The render harness
 * drives [Renderer] callbacks directly; on the JVM there is no real GL thread.
 */
open class GLSurfaceView(context: Context) : View(context) {

    fun setEGLContextClientVersion(version: Int) {}
    fun setEGLConfigChooser(redSize: Int, greenSize: Int, blueSize: Int, alphaSize: Int, depthSize: Int, stencilSize: Int) {}
    fun setRenderer(renderer: Renderer) {}

    var renderMode: Int = RENDERMODE_CONTINUOUSLY
    var preserveEGLContextOnPause: Boolean = false

    fun onPause() {}
    fun onResume() {}

    interface Renderer {
        fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
        fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
        fun onDrawFrame(gl: GL10?)
    }

    companion object {
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
    }
}
