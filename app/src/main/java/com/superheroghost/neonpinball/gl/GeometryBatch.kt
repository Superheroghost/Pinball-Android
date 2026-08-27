package com.superheroghost.neonpinball.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Compiles and links a minimal vertex-color shader program. */
class VertexColorShader {
    private val vertexSource = """
        uniform mat4 u_matrix;
        attribute vec4 a_position;
        attribute vec4 a_color;
        varying vec4 v_color;
        void main() {
          gl_Position = u_matrix * a_position;
          v_color = a_color;
        }
    """.trimIndent()

    private val fragmentSource = """
        precision mediump float;
        varying vec4 v_color;
        void main() {
          gl_FragColor = v_color;
        }
    """.trimIndent()

    var program = 0
        private set
    private var aPosition = 0
    private var aColor = 0
    private var uMatrix = 0

    fun compile(): Boolean {
        program = buildProgram(vertexSource, fragmentSource) ?: return false
        aPosition = GLES20.glGetAttribLocation(program, "a_position")
        aColor = GLES20.glGetAttribLocation(program, "a_color")
        uMatrix = GLES20.glGetUniformLocation(program, "u_matrix")
        return true
    }

    fun use(matrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
    }

    fun bind(buffer: FloatBuffer, strideInts: Int) {
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, strideInts * 4, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, strideInts * 4, buffer)
        buffer.position(0)
    }

    fun unbind() {
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
    }
}

/** Compiles a shader, returning the handle or null on failure (logs reason). */
fun compileShader(type: Int, source: String): Int? {
    val handle = GLES20.glCreateShader(type)
    if (handle == 0) return null
    GLES20.glShaderSource(handle, source)
    GLES20.glCompileShader(handle)
    val status = IntArray(1)
    GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        android.util.Log.e("NeonGL", "shader compile: " + GLES20.glGetShaderInfoLog(handle))
        GLES20.glDeleteShader(handle)
        return null
    }
    return handle
}

/** Links vertex+fragment shaders, logging and cleaning up on failure. */
fun buildProgram(vertexSource: String, fragmentSource: String): Int? {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return null
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
        GLES20.glDeleteShader(vs)
        return null
    }
    val program = GLES20.glCreateProgram()
    if (program == 0) return null
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        android.util.Log.e("NeonGL", "program link: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteProgram(program)
        return null
    }
    return program
}

/**
 * Dynamic triangle batch with per-vertex RGBA. One draw call per flush.
 * All methods are allocation-free in steady state.
 */
class GeometryBatch(initialCapacityVerts: Int = 8192) {
    private var floats = FloatArray(initialCapacityVerts * 6)
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(floats.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var vertexCount = 0

    val size: Int get() = vertexCount

    private fun ensure(extraVerts: Int) {
        if (vertexCount + extraVerts <= floats.size / 6) return
        var cap = floats.size / 6
        while (cap < vertexCount + extraVerts) cap *= 2
        val newArray = FloatArray(cap * 6)
        System.arraycopy(floats, 0, newArray, 0, vertexCount * 6)
        floats = newArray
        buffer = ByteBuffer.allocateDirect(newArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun begin() {
        vertexCount = 0
    }

    fun vertex(x: Float, y: Float, r: Float, g: Float, b: Float, a: Float) {
        ensure(1)
        val o = vertexCount * 6
        floats[o] = x
        floats[o + 1] = y
        floats[o + 2] = r
        floats[o + 3] = g
        floats[o + 4] = b
        floats[o + 5] = a
        vertexCount++
    }

    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, r: Float, g: Float, b: Float, a: Float) {
        vertex(x1, y1, r, g, b, a)
        vertex(x2, y2, r, g, b, a)
        vertex(x3, y3, r, g, b, a)
    }

    fun quad(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, r: Float, g: Float, b: Float, a: Float) {
        tri(x1, y1, x2, y2, x3, y3, r, g, b, a)
        tri(x1, y1, x3, y3, x4, y4, r, g, b, a)
    }

    /** Axis-aligned rectangle. */
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        quad(x, y, x + w, y, x + w, y + h, x, y + h, r, g, b, a)
    }

    /** Solid circle (triangle fan as triangles). */
    fun circle(cx: Float, cy: Float, radius: Float, r: Float, g: Float, b: Float, a: Float, segments: Int = 20) {
        val step = (Math.PI * 2 / segments).toFloat()
        var a0 = 0f
        for (i in 0 until segments) {
            val a1 = a0 + step
            vertex(cx, cy, r, g, b, a)
            vertex(cx + radius * kotlin.math.cos(a0), cy + radius * kotlin.math.sin(a0), r, g, b, a)
            vertex(cx + radius * kotlin.math.cos(a1), cy + radius * kotlin.math.sin(a1), r, g, b, a)
            a0 = a1
        }
    }

    /** Ring/annulus between inner and outer radius, optionally partial. */
    fun ring(cx: Float, cy: Float, inner: Float, outer: Float, r: Float, g: Float, b: Float, a: Float, segments: Int = 28, fromAngle: Float = 0f, sweep: Float = (Math.PI * 2).toFloat()) {
        val step = sweep / segments
        var a0 = fromAngle
        for (i in 0 until segments) {
            val a1 = a0 + step
            val c0x = kotlin.math.cos(a0)
            val c0y = kotlin.math.sin(a0)
            val c1x = kotlin.math.cos(a1)
            val c1y = kotlin.math.sin(a1)
            quad(
                cx + inner * c0x, cy + inner * c0y,
                cx + outer * c0x, cy + outer * c0y,
                cx + outer * c1x, cy + outer * c1y,
                cx + inner * c1x, cy + inner * c1y,
                r, g, b, a,
            )
            a0 = a1
        }
    }

    /**
     * Thick polyline segment with round caps, used for rails and glow.
     * Draw each segment as a quad plus two cap triangles.
     */
    fun lineSegment(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, r: Float, g: Float, b: Float, a: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return
        val nx = -dy / len * width * 0.5f
        val ny = dx / len * width * 0.5f
        quad(
            x1 + nx, y1 + ny,
            x2 + nx, y2 + ny,
            x2 - nx, y2 - ny,
            x1 - nx, y1 - ny,
            r, g, b, a,
        )
        // Round caps.
        circle(x1, y1, width * 0.5f, r, g, b, a, 8)
        circle(x2, y2, width * 0.5f, r, g, b, a, 8)
    }

    /** Draw the batch. Blending mode must already be set. */
    fun flush(shader: VertexColorShader) {
        if (vertexCount == 0) return
        buffer.clear()
        buffer.put(floats, 0, vertexCount * 6)
        buffer.position(0)
        shader.bind(buffer, 6)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        vertexCount = 0
    }

    fun release() {
        buffer.clear()
    }
}
