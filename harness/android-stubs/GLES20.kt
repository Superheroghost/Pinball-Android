package android.opengl

import java.nio.Buffer
import java.nio.FloatBuffer

/**
 * Harness-only stand-in for android.opengl.GLES20: a tiny software
 * rasteriser with the same call signatures as the platform class.
 *
 * It models the parts of GL ES 2.0 the pinball renderer depends on, and
 * deliberately keeps the error semantics that matter:
 *
 *  - drawing with no program in use is a no-op that raises
 *    GL_INVALID_OPERATION (exactly what a real driver does),
 *  - a uniform matrix that was never uploaded stays all-zero, so every
 *    vertex collapses and nothing rasterises,
 *  - disabled vertex attribute arrays fall back to their constant value.
 *
 * That makes it possible to run the real [com.superheroghost.neonpinball.game.PinballRenderer]
 * on a JVM and assert that pixels actually reach the framebuffer.
 */
object GLES20 {
    // ------------------------------------------------------------- constants
    const val GL_TRIANGLES = 0x0004
    const val GL_FLOAT = 0x1406
    const val GL_COLOR_BUFFER_BIT = 0x00004000
    const val GL_DEPTH_TEST = 0x0B71
    const val GL_BLEND = 0x0BE2
    const val GL_ONE = 1
    const val GL_ZERO = 0
    const val GL_SRC_ALPHA = 0x0302
    const val GL_ONE_MINUS_SRC_ALPHA = 0x0303
    const val GL_VERTEX_SHADER = 0x8B31
    const val GL_FRAGMENT_SHADER = 0x8B30
    const val GL_COMPILE_STATUS = 0x8B81
    const val GL_LINK_STATUS = 0x8B82
    const val GL_INVALID_OPERATION = 0x0502

    // ----------------------------------------------------------------- state
    private class Program {
        val uniforms = HashMap<Int, FloatArray>()
        var attribs = HashMap<String, Int>()
    }

    private class Attrib {
        var buffer: FloatBuffer? = null
        var offsetFloats = 0
        var size = 0
        var strideBytes = 0
        var enabled = false
    }

    private var nextHandle = 1
    private val shaders = HashMap<Int, Pair<Int, String>>()
    private val programs = HashMap<Int, Program>()
    private val uniformLocs = HashMap<String, Int>()
    private var currentProgram = 0
    private val attribs = Array(16) { Attrib() }

    private var blendSf = GL_ONE
    private var blendDf = GL_ZERO
    private var clearColor = floatArrayOf(0f, 0f, 0f, 1f)

    /** Framebuffer, RGB triples, top-left origin, values 0..1. */
    private var fb = FloatArray(0)
    var fbWidth = 0
        private set
    var fbHeight = 0
        private set

    // ------------------------------------------------------------- counters
    var drawCalls = 0
        private set
    var ignoredDrawCalls = 0
        private set
    var triangles = 0
        private set
    var glErrors = 0
        private set
    var lastError = 0
        private set

    // --------------------------------------------------------- shader / prog
    fun glCreateShader(type: Int): Int {
        val h = nextHandle++
        shaders[h] = type to ""
        return h
    }

    fun glShaderSource(handle: Int, source: String) {
        val t = shaders[handle]?.first ?: return
        shaders[handle] = t to source
    }

    /** "Compiles" by checking the source declares what the batch relies on. */
    fun glCompileShader(handle: Int) {
        val e = shaders[handle] ?: return
        val src = e.second
        val ok = when (e.first) {
            GL_VERTEX_SHADER -> src.contains("a_position") && src.contains("gl_Position")
            else -> src.contains("gl_FragColor")
        }
        if (ok) shaders[handle] = e.first to src else shaders.remove(handle)
    }

    fun glGetShaderiv(handle: Int, pname: Int, params: IntArray, offset: Int) {
        params[offset] = if (pname == GL_COMPILE_STATUS && shaders.containsKey(handle)) 1 else 0
    }

    fun glGetShaderInfoLog(handle: Int): String = if (shaders.containsKey(handle)) "" else "compile failed"

    fun glDeleteShader(handle: Int) {
        shaders.remove(handle)
    }

    fun glCreateProgram(): Int {
        val h = nextHandle++
        programs[h] = Program()
        return h
    }

    fun glAttachShader(program: Int, shader: Int) {}

    fun glLinkProgram(program: Int) {}

    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
        params[offset] = if (pname == GL_LINK_STATUS && programs.containsKey(program)) 1 else 0
    }

    fun glGetProgramInfoLog(program: Int): String = ""

    fun glDeleteProgram(program: Int) {
        programs.remove(program)
        if (currentProgram == program) currentProgram = 0
    }

    fun glGetAttribLocation(program: Int, name: String): Int {
        val p = programs[program] ?: return -1
        var loc = p.attribs[name]
        if (loc == null) {
            loc = p.attribs.size
            p.attribs[name] = loc
        }
        return loc
    }

    fun glGetUniformLocation(program: Int, name: String): Int {
        if (!programs.containsKey(program)) return -1
        var loc = uniformLocs[name]
        if (loc == null) {
            loc = uniformLocs.size + 1
            uniformLocs[name] = loc
        }
        return loc
    }

    fun glUseProgram(program: Int) {
        currentProgram = if (programs.containsKey(program)) program else 0
    }

    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        val p = programs[currentProgram]
        if (p == null) {
            error()
            return
        }
        p.uniforms[location] = value.copyOfRange(offset, offset + 16)
    }

    // -------------------------------------------------------------- vertices
    fun glEnableVertexAttribArray(index: Int) {
        if (index in attribs.indices) attribs[index].enabled = true
    }

    fun glDisableVertexAttribArray(index: Int) {
        if (index in attribs.indices) attribs[index].enabled = false
    }

    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer) {
        if (index !in attribs.indices || pointer !is FloatBuffer) return
        val a = attribs[index]
        a.buffer = pointer
        a.offsetFloats = pointer.position()
        a.size = size
        a.strideBytes = stride
    }

    // ----------------------------------------------------------------- frame
    fun glViewport(x: Int, y: Int, width: Int, height: Int) {
        if (width == fbWidth && height == fbHeight) return
        fbWidth = width
        fbHeight = height
        fb = FloatArray(width * height * 3)
    }

    fun glClearColor(r: Float, g: Float, b: Float, a: Float) {
        clearColor = floatArrayOf(r, g, b, a)
    }

    fun glClear(mask: Int) {
        if (mask and GL_COLOR_BUFFER_BIT == 0) return
        for (i in 0 until fbWidth * fbHeight) {
            fb[i * 3] = clearColor[0]
            fb[i * 3 + 1] = clearColor[1]
            fb[i * 3 + 2] = clearColor[2]
        }
    }

    fun glEnable(cap: Int) {}
    fun glDisable(cap: Int) {}

    fun glBlendFunc(sfactor: Int, dfactor: Int) {
        blendSf = sfactor
        blendDf = dfactor
    }

    fun glDrawArrays(mode: Int, first: Int, count: Int) {
        drawCalls++
        val p = programs[currentProgram]
        if (p == null) {
            // Real GL ES 2.0: INVALID_OPERATION, nothing is drawn.
            ignoredDrawCalls++
            error()
            return
        }
        val m = p.uniforms.values.firstOrNull() ?: FloatArray(16)
        val pos = attribs.getOrNull(0)
        val col = attribs.getOrNull(1)
        if (pos == null || col == null) {
            ignoredDrawCalls++
            error()
            return
        }
        val pv = FloatArray(4)
        val cv = FloatArray(4)
        val sx = FloatArray(3)
        val sy = FloatArray(3)
        val cr = FloatArray(3)
        val cg = FloatArray(3)
        val cb = FloatArray(3)
        val ca = FloatArray(3)
        var i = first
        while (i + 2 < first + count) {
            var ok = true
            for (k in 0..2) {
                readAttrib(pos, i + k, pv)
                readAttrib(col, i + k, cv)
                val cx = m[0] * pv[0] + m[4] * pv[1] + m[12]
                val cy = m[1] * pv[0] + m[5] * pv[1] + m[13]
                val cw = m[3] * pv[0] + m[7] * pv[1] + m[15]
                if (cw == 0f) {
                    ok = false
                    break
                }
                val nx = cx / cw
                val ny = cy / cw
                sx[k] = (nx * 0.5f + 0.5f) * fbWidth
                sy[k] = (1f - (ny * 0.5f + 0.5f)) * fbHeight
                cr[k] = cv[0]; cg[k] = cv[1]; cb[k] = cv[2]; ca[k] = cv[3]
            }
            if (ok) raster(sx, sy, cr, cg, cb, ca)
            i += 3
        }
    }

    private fun readAttrib(a: Attrib, vert: Int, out: FloatArray) {
        val b = a.buffer
        if (!a.enabled || b == null) {
            // GL ES 2.0: a disabled array falls back to the constant
            // generic attribute value, which defaults to (0, 0, 0, 1).
            out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f
            return
        }
        val strideF = if (a.strideBytes == 0) a.size else a.strideBytes / 4
        for (c in out.indices) {
            val idx = a.offsetFloats + vert * strideF + c
            out[c] = if (c < a.size && idx in 0 until b.limit()) b.get(idx) else if (c == 3) 1f else 0f
        }
    }

    private fun raster(
        x: FloatArray,
        y: FloatArray,
        vr: FloatArray,
        vg: FloatArray,
        vb: FloatArray,
        va: FloatArray,
    ) {
        val area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
        if (area > -1e-9f && area < 1e-9f) return
        val minX = Math.floor(minOf(x[0], x[1], x[2]).toDouble()).toInt().coerceIn(0, fbWidth - 1)
        val maxX = Math.ceil(maxOf(x[0], x[1], x[2]).toDouble()).toInt().coerceIn(0, fbWidth - 1)
        val minY = Math.floor(minOf(y[0], y[1], y[2]).toDouble()).toInt().coerceIn(0, fbHeight - 1)
        val maxY = Math.ceil(maxOf(y[0], y[1], y[2]).toDouble()).toInt().coerceIn(0, fbHeight - 1)
        for (py in minY..maxY) {
            val cy = py + 0.5f
            for (px in minX..maxX) {
                val cx = px + 0.5f
                // Barycentric weights from signed sub-areas.
                val e0 = (x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)
                val e1 = (x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)
                val e2 = area - e0 - e1
                val inside = if (area > 0f) e0 >= 0f && e1 >= 0f && e2 >= 0f else e0 <= 0f && e1 <= 0f && e2 <= 0f
                if (!inside) continue
                val w0 = e0 / area
                val w1 = e1 / area
                val w2 = 1f - w0 - w1
                blendPixel(
                    px, py,
                    vr[0] * w0 + vr[1] * w1 + vr[2] * w2,
                    vg[0] * w0 + vg[1] * w1 + vg[2] * w2,
                    vb[0] * w0 + vb[1] * w1 + vb[2] * w2,
                    va[0] * w0 + va[1] * w1 + va[2] * w2,
                )
                triangles++
            }
        }
    }

    private fun blendPixel(px: Int, py: Int, r: Float, g: Float, b: Float, a: Float) {
        val o = (py * fbWidth + px) * 3
        if (o + 2 >= fb.size) return
        val srcScale = if (blendSf == GL_SRC_ALPHA) a else 1f
        val dstScale = when (blendDf) {
            GL_ONE_MINUS_SRC_ALPHA -> 1f - a
            GL_ONE -> 1f
            else -> 0f
        }
        fb[o] = (r * srcScale + fb[o] * dstScale).coerceIn(0f, 1f)
        fb[o + 1] = (g * srcScale + fb[o + 1] * dstScale).coerceIn(0f, 1f)
        fb[o + 2] = (b * srcScale + fb[o + 2] * dstScale).coerceIn(0f, 1f)
    }

    private fun error() {
        glErrors++
        lastError = GL_INVALID_OPERATION
    }

    // --------------------------------------------------------------- results
    fun pixel(x: Int, y: Int): FloatArray {
        val o = (y * fbWidth + x) * 3
        return floatArrayOf(fb[o], fb[o + 1], fb[o + 2])
    }

    /** Pixels whose colour differs from the clear colour by > 2/255. */
    fun nonBackgroundPixels(): Int {
        var n = 0
        val eps = 2f / 255f
        for (i in 0 until fbWidth * fbHeight) {
            val o = i * 3
            if (Math.abs(fb[o] - clearColor[0]) > eps ||
                Math.abs(fb[o + 1] - clearColor[1]) > eps ||
                Math.abs(fb[o + 2] - clearColor[2]) > eps
            ) n++
        }
        return n
    }

    /** Distinct colours (quantised to 1/16) present in the framebuffer. */
    fun distinctColors(): Int {
        val set = HashSet<Int>()
        for (i in 0 until fbWidth * fbHeight) {
            val o = i * 3
            set.add(((fb[o] * 15).toInt() shl 8) or ((fb[o + 1] * 15).toInt() shl 4) or (fb[o + 2] * 15).toInt())
        }
        return set.size
    }

    /** ASCII brightness map, for eyeballing the frame in a terminal. */
    fun ascii(cols: Int, rows: Int): String {
        val ramp = " .:-=+*#%@"
        val sb = StringBuilder()
        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val x = ((rx + 0.5f) / cols * fbWidth).toInt().coerceIn(0, fbWidth - 1)
                val y = ((ry + 0.5f) / rows * fbHeight).toInt().coerceIn(0, fbHeight - 1)
                val p = pixel(x, y)
                val lum = (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]).coerceIn(0f, 1f)
                sb.append(ramp[(lum * (ramp.length - 1)).toInt().coerceIn(0, ramp.length - 1)])
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Write the framebuffer as a binary PPM (P6) image. */
    fun writePpm(path: String) {
        val bytes = ByteArray(fbWidth * fbHeight * 3)
        for (i in 0 until fbWidth * fbHeight) {
            for (c in 0..2) {
                bytes[i * 3 + c] = (fb[i * 3 + c].coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        }
        val header = "P6\n$fbWidth $fbHeight\n255\n".toByteArray(Charsets.US_ASCII)
        java.io.File(path).outputStream().use { out ->
            out.write(header)
            out.write(bytes)
        }
    }
}
