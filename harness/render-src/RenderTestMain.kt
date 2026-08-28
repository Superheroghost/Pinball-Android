package com.superheroghost.neonpinball.harness

import android.opengl.GLES20
import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.game.ParticleSystem
import com.superheroghost.neonpinball.game.PinballRenderer
import com.superheroghost.neonpinball.sim.GameSim

/**
 * Headless render smoke test.
 *
 * Runs the *real* PinballRenderer against the software GL stub in
 * harness/android-stubs and asserts that a frame actually reaches the
 * framebuffer. This is what catches "the playfield never shows up" bugs:
 * a renderer that forgets to activate its shader program, upload the
 * camera matrix, or enable its vertex attribute arrays issues draw calls
 * that a driver silently turns into GL_INVALID_OPERATION, leaving the
 * player staring at the clear colour.
 */
object RenderTestMain {

    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String) {
        println((if (ok) "  PASS  " else "  FAIL  ") + name.padEnd(34) + detail)
        if (!ok) failures++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val width = 412 // Pixel 10 Pro logical width, scaled down 2.6x
        val height = 892

        val sim = GameSim()
        val session = GameSession(sim, object : GameSession.Listener {})
        val particles = ParticleSystem()
        val renderer = PinballRenderer(sim, session, particles)

        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, width, height)
        session.startGame()

        // ~1 s of frames at 60 Hz.
        repeat(60) { renderer.onDrawFrame(null) }

        val total = width * height
        val lit = GLES20.nonBackgroundPixels()
        val pct = lit * 100f / total

        println("== render smoke test")
        println("   framebuffer ${GLES20.fbWidth}x${GLES20.fbHeight}, draw calls=${GLES20.drawCalls}, " +
            "ignored=${GLES20.ignoredDrawCalls}, triangles=${GLES20.triangles}, glErrors=${GLES20.glErrors}")
        println("   non-background pixels: $lit / $total (${String.format("%.1f", pct)}%), " +
            "distinct colours=${GLES20.distinctColors()}")

        check("framebuffer was sized", GLES20.fbWidth == width && GLES20.fbHeight == height, "${GLES20.fbWidth}x${GLES20.fbHeight}")
        check("renderer issued draw calls", GLES20.drawCalls > 0, "calls=${GLES20.drawCalls}")
        check("no draw call was ignored by GL", GLES20.ignoredDrawCalls == 0, "ignored=${GLES20.ignoredDrawCalls}")
        check("geometry rasterised", GLES20.triangles > 1000, "fragments=${GLES20.triangles}")
        // Background alone is ~1.5% of pixels (glow band + scanlines); the full
        // table fills a large part of the frame.
        check("playfield is visible", pct > 40f, String.format("%.1f%%", pct) + " of frame")
        check("frame is not monochrome", GLES20.distinctColors() > 8, "colours=${GLES20.distinctColors()}")

        // Spot-check known playfield features by screen position.
        val centre = GLES20.pixel(width / 2, height / 2)
        val ballArea = sampleRegion(width / 2, (height * 0.88f).toInt(), 40)
        println("   centre pixel rgb=${fmt(centre)}  lower-centre max rgb=${fmt(ballArea)}")
        check("centre of frame is not clear colour", maxOf(centre[0], centre[1], centre[2]) > 0.05f, fmt(centre))

        val out = "build/render-frame.ppm"
        java.io.File("build").mkdirs()
        GLES20.writePpm(out)
        println("   frame written to $out")

        println()
        print(GLES20.ascii(46, 60))

        println()
        if (failures == 0) {
            println("RENDER SMOKE TEST PASS")
        } else {
            println("RENDER SMOKE TEST: $failures FAILURE(S)")
            kotlin.system.exitProcess(1)
        }
    }

    private fun sampleRegion(cx: Int, cy: Int, half: Int): FloatArray {
        val best = floatArrayOf(0f, 0f, 0f)
        for (y in cy - half..cy + half) {
            for (x in cx - half..cx + half) {
                if (x < 0 || y < 0 || x >= GLES20.fbWidth || y >= GLES20.fbHeight) continue
                val p = GLES20.pixel(x, y)
                for (i in 0..2) if (p[i] > best[i]) best[i] = p[i]
            }
        }
        return best
    }

    private fun fmt(p: FloatArray): String =
        String.format("(%.2f, %.2f, %.2f)", p[0], p[1], p[2])
}
