package com.superheroghost.neonpinball.game

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.superheroghost.neonpinball.gl.Camera2D
import com.superheroghost.neonpinball.gl.GeometryBatch
import com.superheroghost.neonpinball.gl.VertexColorShader
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.TableTuning
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "NeonRenderer"

/** Palette. */
object Palette {
    const val BG_TOP_R = 0.016f
    const val BG_TOP_G = 0.020f
    const val BG_TOP_B = 0.043f
    const val BG_BOT_R = 0.043f
    const val BG_BOT_G = 0.031f
    const val BG_BOT_B = 0.090f

    const val CYAN_R = 0.13f
    const val CYAN_G = 0.90f
    const val CYAN_B = 1.00f
    const val VIOLET_R = 0.48f
    const val VIOLET_G = 0.36f
    const val VIOLET_B = 1.00f
    const val MAGENTA_R = 1.00f
    const val MAGENTA_G = 0.24f
    const val MAGENTA_B = 0.75f
    const val GOLD_R = 1.00f
    const val GOLD_G = 0.71f
    const val GOLD_B = 0.33f
    const val STEEL_R = 0.62f
    const val STEEL_G = 0.72f
    const val STEEL_B = 0.88f
    const val INK_R = 0.035f
    const val INK_G = 0.043f
    const val INK_B = 0.078f
}

/**
 * Real-time OpenGL ES 2.0 renderer for the pinball table. Everything is drawn
 * procedurally through a single vertex-color batch; glow is layered additive
 * geometry. No textures, no allocations in the steady state.
 */
class PinballRenderer(
    private val sim: GameSim,
    private val session: GameSession?,
    private val particles: ParticleSystem,
) : GLSurfaceView.Renderer {

    val camera = Camera2D()
    private val shader = VertexColorShader()
    private val batch = GeometryBatch(32768)

    /** Visual intensity scale (settings: reduced effects). */
    var fxScale = 1f

    /** White flash amount 0..1 (set by game events). */
    var flash = 0f

    private var timeS = 0f
    private var lastNanos = 0L

    /** False if the shader could not be built; drawing is skipped. */
    private var shaderReady = false

    /** Guards the one-shot first-frame diagnostic log. */
    private var diagnosticsLogged = false

    /** Interpolation alpha for the current frame. */
    private var alpha = 1f

    /** Rollover lane flash timers (indexed by lane id). */
    private val laneFlash = FloatArray(16)

    /** Score popups (simple floating text markers). */
    private class Popup {
        var x = 0f
        var y = 0f
        var life = 0f
        var size = 1f
        var gold = false
    }

    private val popups = Array(12) { Popup() }
    private var popupCursor = 0

    /** Callback so the game can drive haptics/audio from renderer events. */
    var onFrame: ((dt: Float) -> Unit)? = null

    /** Fixed physics stepping (mirrors GameSim but keeps render smooth). */
    val gameLoop = GameLoop(sim)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.008f, 0.010f, 0.020f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        shaderReady = shader.compile()
        if (!shaderReady) {
            android.util.Log.e(TAG, "vertex-colour shader failed to build; table cannot render")
        }
        android.util.Log.i(
            TAG,
            "surface created: program=${shader.program} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
                "gl=${GLES20.glGetString(GLES20.GL_VERSION)}",
        )
        gameLoop.start()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = if (lastNanos == 0L) 1f / 60f else (now - lastNanos) / 1e9f
        lastNanos = now
        dt = dt.coerceIn(1f / 240f, 0.05f)
        timeS += dt

        // Fixed-step the simulation on the render thread (single-threaded
        // game+render keeps determinism and avoids sync overhead).
        gameLoop.update(dt)
        alpha = gameLoop.interpolationAlpha
        onFrame?.invoke(dt)

        camera.update(dt)
        camera.apply()
        particles.update(dt)
        updateTrails()
        for (p in popups) if (p.life > 0f) p.life -= dt
        flash = (flash - dt * 3f).coerceAtLeast(0f)
        for (i in laneFlash.indices) laneFlash[i] = (laneFlash[i] - dt * 3.5f).coerceAtLeast(0f)

        draw()
    }

    fun markLane(id: Int) {
        if (id in laneFlash.indices) laneFlash[id] = 1f
    }

    fun popup(x: Float, y: Float, gold: Boolean, size: Float = 1f) {
        val p = popups[popupCursor]
        popupCursor = (popupCursor + 1) % popups.size
        p.x = x
        p.y = y
        p.life = 0.9f
        p.size = size
        p.gold = gold
    }

    // ---------------------------------------------------------------- draw

    private fun draw() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // The vertex-colour program must be current *before* any geometry is
        // submitted. glDrawArrays with program 0 bound is a no-op, so without
        // this the whole table silently disappears behind the clear colour.
        if (!shaderReady) return
        shader.use(camera.viewProjMatrix())

        drawBackground()
        drawPlayfield()
        drawLanes()
        drawRuleLights()
        drawRamp()
        drawElements()
        drawFlippers()
        drawPlunger()
        drawBalls()
        drawParticles()
        drawPopups()
        drawVignette()
        logFirstFrame()
    }

    /**
     * One-shot diagnostic: if the table ever fails to appear this records
     * whether the program was bound, what the camera resolved to and whether
     * GL reported an error, instead of leaving a silently black surface.
     */
    private fun logFirstFrame() {
        if (diagnosticsLogged) return
        diagnosticsLogged = true
        android.util.Log.i(
            TAG,
            "first frame: ${camera.screenWidth}x${camera.screenHeight} program=${shader.program} " +
                "world=[${"%.3f".format(camera.worldLeft)}, ${"%.3f".format(camera.worldBottom)} .. " +
                "${"%.3f".format(camera.worldRight)}, ${"%.3f".format(camera.worldTop)}] " +
                "balls=${sim.balls.size} glError=${GLES20.glGetError()}",
        )
    }

    private fun normalBlend() = GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    private fun additiveBlend() = GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

    private fun drawBackground() {
        normalBlend()
        batch.begin()
        val w = camera.worldRight - camera.worldLeft
        batch.rect(
            camera.worldLeft, camera.worldBottom, w, camera.worldTop - camera.worldBottom,
            Palette.BG_BOT_R, Palette.BG_BOT_G, Palette.BG_BOT_B, 1f,
        )
        // Horizontal glow band rising from the flipper area.
        val cy = 0.24f
        val glow = 0.5f + 0.5f * sin(timeS * 0.7)
        for (i in 0 until 6) {
            val t = i / 5f
            batch.rect(
                camera.worldLeft, cy - 0.10f + t * 0.10f, w, 0.035f,
                Palette.VIOLET_R * 0.5f, Palette.VIOLET_G * 0.5f, Palette.VIOLET_B * 0.5f,
                (0.030f * (1f - t) * (0.7f + 0.3f * glow) * fxScale).toFloat(),
            )
        }
        // Faint scanline grid.
        if (fxScale > 0.5f) {
            val step = 0.06f
            var x = camera.worldLeft - (camera.worldLeft % step)
            while (x < camera.worldRight) {
                batch.rect(x, camera.worldBottom, 0.0012f, camera.worldTop - camera.worldBottom, 0.5f, 0.8f, 1f, 0.035f)
                x += step
            }
            var y = camera.worldBottom - (camera.worldBottom % step)
            while (y < camera.worldTop) {
                batch.rect(camera.worldLeft, y, w, 0.0012f, 0.5f, 0.8f, 1f, 0.03f)
                y += step
            }
        }
        batch.flush(shader)
    }

    /** Playfield slab: rounded-top shape bounded by the arch. */
    private fun drawPlayfield() {
        normalBlend()
        batch.begin()
        val t = TableTuning
        val cx = t.TABLE_W * 0.5f
        val cy = t.ARCH_CY
        val r = t.ARCH_R
        // Main slab under the arch.
        val segs = 40
        val a0 = 0f
        val a1 = Math.PI.toFloat()
        val px = FloatArray(segs + 2)
        val py = FloatArray(segs + 2)
        px[0] = 0f
        py[0] = 0f
        for (i in 0..segs) {
            val a = a0 + (a1 - a0) * i / segs
            px[i + 1] = cx + r * cos(a)
            py[i + 1] = cy + r * sin(a)
        }
        for (i in 0 until segs) {
            batch.tri(
                px[0], py[0],
                px[i + 1], py[i + 1],
                px[i + 2], py[i + 2],
                Palette.INK_R, Palette.INK_G, Palette.INK_B, 0.92f,
            )
        }
        // Fill the rectangle part below the arch centreline.
        batch.rect(0f, 0f, t.TABLE_W, cy, Palette.INK_R, Palette.INK_G, Palette.INK_B, 0.92f)
        // Two soft vertical washes for depth.
        additiveBlend()
        for (i in 0 until 5) {
            val g = 0.020f * (1f - i / 5f) * fxScale
            batch.rect(0.02f + i * 0.002f, 0.1f, 0.13f, 0.8f, Palette.VIOLET_R, Palette.VIOLET_G, Palette.VIOLET_B, g)
            batch.rect(t.TABLE_W - 0.15f - i * 0.002f, 0.1f, 0.13f, 0.8f, Palette.VIOLET_R, Palette.VIOLET_G, Palette.VIOLET_B, g)
        }
        batch.flush(shader)
    }

    /** Rollover lanes + their lights, top lane dividers etc. */
    private fun drawLanes() {
        normalBlend()
        batch.begin()
        // Lane rollover markers.
        for ((id, ro) in sim.table.rollovers) {
            val flash = laneFlash[ro.id]
            val lit = flash > 0f
            val baseA = 0.30f
            val a = if (lit) baseA + flash * 0.7f else baseA
            val r = if (lit) Palette.GOLD_R else Palette.CYAN_R
            val g = if (lit) Palette.GOLD_G else Palette.CYAN_G
            val b = if (lit) Palette.GOLD_B else Palette.CYAN_B
            batch.rect(ro.x - ro.w * 0.32f, ro.y - ro.h * 0.32f, ro.w * 0.64f, ro.h * 0.64f, r, g, b, a)
        }
        batch.flush(shader)
        additiveBlend()
        batch.begin()
        for ((id, ro) in sim.table.rollovers) {
            val flash = laneFlash[ro.id]
            if (flash <= 0f) continue
            batch.rect(ro.x - ro.w * 0.45f, ro.y - ro.h * 0.45f, ro.w * 0.9f, ro.h * 0.9f, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, flash * 0.35f * fxScale)
        }
        batch.flush(shader)
    }

    /**
     * Rule-state inserts: lanes, bonus multiplier ladder, lock, jackpot,
     * super, extra ball, ball save bar, objectives, overdrive pulse.
     */
    private fun drawRuleLights() {
        val rules = session?.rules ?: return
        val t = sim.table
        val blink = 0.5f + 0.5f * kotlin.math.sin(timeS * 8f)

        additiveBlend()
        batch.begin()

        // ---- top lanes: N E X inserts under the rollovers.
        val laneIds = intArrayOf(com.superheroghost.neonpinball.sim.Ids.LANE_N, com.superheroghost.neonpinball.sim.Ids.LANE_E, com.superheroghost.neonpinball.sim.Ids.LANE_X)
        for (i in laneIds.indices) {
            val ro = t.rollovers[laneIds[i]] ?: continue
            val done = rules.lanesDone[i]
            val isSkill = rules.skillShotLane == i && rules.skillShotTimeLeft > 0f
            val a = if (isSkill) 0.35f + 0.6f * blink else if (done) 0.85f else 0.18f
            val rr = if (isSkill) Palette.GOLD_R else Palette.CYAN_R
            val gg = if (isSkill) Palette.GOLD_G else Palette.CYAN_G
            val bb = if (isSkill) Palette.GOLD_B else Palette.CYAN_B
            // Arrow chevron under the lane.
            val cx = ro.x
            val cy = ro.y - 0.022f
            batch.tri(cx, cy - 0.007f, cx - 0.007f, cy + 0.004f, cx + 0.007f, cy + 0.004f, rr, gg, bb, a)
            batch.tri(cx, cy + 0.004f, cx - 0.007f, cy + 0.011f, cx + 0.007f, cy + 0.011f, rr, gg, bb, a * 0.5f)
        }

        // ---- bonus multiplier ladder: 5 dots (2x..6x) along the arch left.
        for (i in 0 until 5) {
            val lit = rules.bonusMultiplier >= i + 2
            val x = 0.062f + i * 0.016f
            val y = 0.885f - i * 0.0175f
            batch.circle(x, y, 0.0052f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, if (lit) 0.9f else 0.2f, 10)
        }

        // ---- lock light at the ramp mouth.
        val lockLit = rules.lockLit
        run {
            val x = 0.193f
            val y = 0.872f
            val a = if (lockLit) 0.45f + 0.5f * blink else 0.15f
            batch.ring(x, y, 0.006f, 0.009f, if (lockLit) Palette.GOLD_R else Palette.CYAN_R, if (lockLit) Palette.GOLD_G else Palette.CYAN_G, if (lockLit) Palette.GOLD_B else Palette.CYAN_B, a, 12)
        }

        // ---- locked balls indicators beside the scoop.
        for (i in 0 until 2) {
            val lit = rules.ballsLocked > i
            val x = 0.121f + i * 0.014f
            val y = 0.958f
            batch.circle(x, y, 0.0045f, if (lit) Palette.VIOLET_R else Palette.CYAN_R, if (lit) Palette.VIOLET_G else Palette.CYAN_G, if (lit) Palette.VIOLET_B else Palette.CYAN_B, if (lit) 0.9f else 0.2f, 8)
        }

        // ---- jackpot ring on the scoop during multiball.
        if (rules.multiballActive) {
            val sc = t.scoop
            val a = 0.4f + 0.5f * blink
            batch.ring(sc.x, sc.y, 0.016f, 0.021f, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, a, 16)
            // Jackpot progress pips.
            for (i in 0 until 3) {
                val lit = rules.jackpotsCollected > i
                batch.circle(0.168f + i * 0.014f, 0.958f, 0.0045f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, if (lit) 0.95f else 0.2f, 8)
            }
        }

        // ---- super jackpot star at the orbit apex.
        run {
            val lit = rules.superJackpotLit
            val a = if (lit) 0.5f + 0.5f * blink else 0.12f
            val x = 0.235f
            val y = 0.965f
            for (k in 0 until 4) {
                val ang = timeS * (if (lit) 2.2f else 0.35f) + k * (Math.PI.toFloat() * 0.5f)
                val dx = kotlin.math.cos(ang) * 0.0085f
                val dy = kotlin.math.sin(ang) * 0.0085f
                batch.lineSegment(x - dx, y - dy, x + dx, y + dy, 0.0032f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, a)
            }
        }

        // ---- extra ball arrow in the left outlane top.
        run {
            val lit = rules.extraBallLit
            val a = if (lit) 0.45f + 0.55f * blink else 0.1f
            val x = 0.073f
            val y = 0.240f
            batch.tri(x, y - 0.008f, x - 0.007f, y + 0.005f, x + 0.007f, y + 0.005f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, a)
            batch.tri(x, y - 0.014f, x - 0.007f, y - 0.003f, x + 0.007f, y - 0.003f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, a * 0.6f)
        }

        // ---- ball save bar across the drain mouth.
        if (rules.ballSaveTimeLeft > 0f && rules.playing) {
            val k = rules.ballSaveTimeLeft / com.superheroghost.neonpinball.sim.RulesEngine.BALL_SAVE_TIME
            val a = 0.35f + 0.45f * blink * k
            batch.rect(0.125f, 0.045f, (TableTuning.SHOOTER_X_INNER - 0.243f) * k, 0.006f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, a)
        }

        // ---- objective inserts: 6 pips down the left wall.
        for (i in 0 until 6) {
            val lit = rules.objectivesDone[i]
            val x = 0.048f
            val y = 0.60f - i * 0.052f
            val a = if (lit) 0.85f else 0.15f
            batch.tri(x + 0.007f, y, x - 0.005f, y - 0.0055f, x - 0.005f, y + 0.0055f, if (lit) Palette.GOLD_R else Palette.CYAN_R, if (lit) Palette.GOLD_G else Palette.CYAN_G, if (lit) Palette.GOLD_B else Palette.CYAN_B, a)
        }

        batch.flush(shader)

        // ---- overdrive: pulsing edge glow.
        if (rules.overdriveTimeLeft > 0f) {
            batch.begin()
            val k = rules.overdriveTimeLeft / com.superheroghost.neonpinball.sim.RulesEngine.OVERDRIVE_TIME
            val a = (0.10f + 0.10f * blink) * k * fxScale
            val w = TableTuning.TABLE_W
            val h = TableTuning.TABLE_H
            batch.rect(0f, 0f, 0.012f, h, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, a)
            batch.rect(w - 0.012f, 0f, 0.012f, h, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, a)
            batch.rect(0f, h - 0.012f, w, 0.012f, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, a)
            batch.flush(shader)
        }
    }

    /** Ramp tube: elevated channel rendered as rails + glow. */
    private fun drawRamp() {
        val path = sim.table.rampPath
        if (path.size < 2) return
        normalBlend()
        batch.begin()
        // Deck shadow under the tube.
        for (i in 0 until path.size - 1) {
            batch.lineSegment(path[i].x, path[i].y - 0.004f, path[i + 1].x, path[i + 1].y - 0.004f, 0.030f, 0.01f, 0.012f, 0.03f, 0.55f)
        }
        batch.flush(shader)
        additiveBlend()
        batch.begin()
        // Glow.
        for (i in 0 until path.size - 1) {
            batch.lineSegment(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, 0.016f, Palette.VIOLET_R, Palette.VIOLET_G, Palette.VIOLET_B, 0.16f * fxScale)
        }
        batch.flush(shader)
        normalBlend()
        batch.begin()
        // Rails.
        for (i in 0 until path.size - 1) {
            batch.lineSegment(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, 0.010f, 0.26f, 0.22f, 0.42f, 0.95f)
            batch.lineSegment(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, 0.004f, Palette.VIOLET_R, Palette.VIOLET_G, Palette.VIOLET_B, 0.9f)
        }
        batch.flush(shader)
    }

    private fun drawElements() {
        val table = sim.table

        // ---- walls: glow pass then core
        additiveBlend()
        batch.begin()
        for (pts in table.wallRuns) drawPolyline(pts, 0.010f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 0.10f * fxScale)
        drawPolyline(outerFloats, 0.014f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 0.13f * fxScale)
        batch.flush(shader)

        normalBlend()
        batch.begin()
        for (pts in table.wallRuns) drawPolyline(pts, 0.005f, Palette.STEEL_R, Palette.STEEL_G, Palette.STEEL_B, 0.9f)
        drawPolyline(outerFloats, 0.0075f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 0.95f)
        batch.flush(shader)

        // ---- bumpers
        for (bumper in table.bumpers) {
            val x = bumper.x
            val y = bumper.y
            val r = TableTuning.BUMPER_R
            val pulse = bumper.pulse
            normalBlend()
            batch.begin()
            batch.circle(x, y, r * 1.22f, 0.09f, 0.10f, 0.18f, 0.9f) // dark base
            batch.circle(x, y, r, Palette.VIOLET_R * 0.5f, Palette.VIOLET_G * 0.5f, Palette.VIOLET_B * 0.55f, 1f)
            batch.ring(x, y, r * 0.62f, r * 0.82f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 0.9f)
            batch.circle(x, y, r * 0.30f, 0.9f, 0.95f, 1f, 0.85f)
            batch.flush(shader)
            additiveBlend()
            batch.begin()
            batch.ring(x, y, r * 0.95f, r * (1.05f + pulse * 0.35f), Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, (0.25f + pulse * 0.75f) * fxScale)
            if (pulse > 0f) batch.circle(x, y, r * 1.5f * pulse, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, pulse * 0.30f * fxScale)
            batch.flush(shader)
        }

        // ---- slingshots
        for (sling in table.slings) {
            normalBlend()
            batch.begin()
            // Triangle fill from the pulse-lit outline.
            val verts = slingVerts(sling)
            batch.tri(verts[0], verts[1], verts[2], verts[3], verts[4], verts[5], 0.10f, 0.12f, 0.22f, 0.92f)
            batch.flush(shader)
            additiveBlend()
            batch.begin()
            val cr = if (sling.left) Palette.CYAN_R else Palette.MAGENTA_R
            val cg = if (sling.left) Palette.CYAN_G else Palette.MAGENTA_G
            val cb = if (sling.left) Palette.CYAN_B else Palette.MAGENTA_B
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                batch.lineSegment(verts[i * 2], verts[i * 2 + 1], verts[j * 2], verts[j * 2 + 1], 0.006f + sling.pulse * 0.010f, cr, cg, cb, (0.55f + sling.pulse * 0.45f) * fxScale)
            }
            batch.flush(shader)
        }

        // ---- drop targets
        for (target in table.dropBank.targets) {
            val drop = target.dropAnim
            val h = 0.013f * (1f - drop * 0.85f)
            normalBlend()
            batch.begin()
            batch.rect(target.x - 0.011f, target.y - h * 0.5f, 0.022f, h, Palette.MAGENTA_R, Palette.MAGENTA_G, Palette.MAGENTA_B, if (target.down) 0.35f else 0.95f)
            batch.flush(shader)
            additiveBlend()
            batch.begin()
            if (!target.down) {
                batch.rect(target.x - 0.012f, target.y - h * 0.6f, 0.024f, h * 1.2f, Palette.MAGENTA_R, Palette.MAGENTA_G, Palette.MAGENTA_B, (0.25f + target.pulse * 0.5f) * fxScale)
            }
            batch.flush(shader)
        }

        // ---- standups
        for (s in table.standups.values) {
            normalBlend()
            batch.begin()
            batch.rect(s.x - 0.009f, s.y - 0.007f, 0.018f, 0.014f, Palette.GOLD_R * 0.9f, Palette.GOLD_G * 0.7f, Palette.GOLD_B * 0.4f, 0.95f)
            batch.flush(shader)
            additiveBlend()
            batch.begin()
            batch.rect(s.x - 0.011f, s.y - 0.009f, 0.022f, 0.018f, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, (0.20f + s.pulse * 0.5f) * fxScale)
            batch.flush(shader)
        }

        // ---- spinner
        val sp = table.spinner
        normalBlend()
        batch.begin()
        val spinA = sp.angle
        batch.lineSegment(
            sp.x - cos(spinA) * 0.012f, sp.y - sin(spinA) * 0.026f,
            sp.x + cos(spinA) * 0.012f, sp.y + sin(spinA) * 0.026f,
            0.005f, Palette.GOLD_R, Palette.GOLD_G, Palette.GOLD_B, 0.95f,
        )
        batch.flush(shader)

        // ---- gate
        val gate = table.shooterGate
        normalBlend()
        batch.begin()
        val swing = gate.swing
        val gx1 = 0.455f
        val gy1 = 0.933f
        val gx2 = 0.500f
        val gy2 = 0.883f
        val mx = gx1 + (gx2 - gx1) * swing
        val my = gy1 + (gy2 - gy1) * swing
        batch.lineSegment(gx1, gy1, mx, my, 0.005f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 0.9f)
        batch.flush(shader)

        // ---- scoop
        val scoop = table.scoop
        normalBlend()
        batch.begin()
        batch.circle(scoop.x, scoop.y, scoop.r, 0.005f, 0.006f, 0.012f, 1f)
        batch.flush(shader)
        additiveBlend()
        batch.begin()
        batch.ring(scoop.x, scoop.y, scoop.r, scoop.r + 0.005f, Palette.VIOLET_R, Palette.VIOLET_G, Palette.VIOLET_B, (0.35f + scoop.captureFlash * 0.65f) * fxScale)
        batch.flush(shader)
    }

    private fun slingVerts(sling: com.superheroghost.neonpinball.sim.Slingshot): FloatArray = sling.outline

    private fun drawPolyline(pts: FloatArray, width: Float, r: Float, g: Float, b: Float, a: Float) {
        var i = 0
        while (i < pts.size - 2) {
            batch.lineSegment(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], width, r, g, b, a)
            i += 2
        }
    }

    private fun toFloats(list: List<org.jbox2d.common.Vec2>): FloatArray {
        val f = FloatArray(list.size * 2)
        for (i in list.indices) {
            f[i * 2] = list[i].x
            f[i * 2 + 1] = list[i].y
        }
        return f
    }

    private val outerFloats by lazy { toFloats(sim.table.outerBoundary) }

    private fun drawFlippers() {
        for (flipper in listOf(sim.table.flipperL, sim.table.flipperR)) {
            val ang = flipper.angle
            val px = flipper.pivotX
            val py = flipper.pivotY
            val len = TableTuning.FLIPPER_LEN
            val rb = TableTuning.FLIPPER_BASE_R
            val rt = TableTuning.FLIPPER_TIP_R
            val tipX = px + cos(ang) * len
            val tipY = py + sin(ang) * len
            val act = flipper.activation

            // Glow when active.
            additiveBlend()
            batch.begin()
            batch.lineSegment(px, py, tipX, tipY, 0.030f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, (0.06f + act * 0.34f) * fxScale)
            batch.flush(shader)

            normalBlend()
            batch.begin()
            batch.lineSegment(px, py, tipX, tipY, rb * 2f * 0.92f, 0.16f, 0.20f, 0.34f, 1f)
            batch.lineSegment(px, py, tipX, tipY, rb * 1.1f, Palette.CYAN_R * 0.8f, Palette.CYAN_G * 0.8f, Palette.CYAN_B * 0.85f, 1f)
            batch.circle(px, py, rb, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, 1f)
            batch.circle(tipX, tipY, rt, 0.85f, 0.95f, 1f, 1f)
            batch.flush(shader)
        }
    }

    private fun drawPlunger() {
        val t = TableTuning
        val laneCx = (t.SHOOTER_X_INNER + t.TABLE_W) * 0.5f
        val pull = sim.plungerPull
        val rodTop = t.PLUNGER_Y - pull * 0.070f

        normalBlend()
        batch.begin()
        // Rod.
        batch.rect(laneCx - 0.006f, 0.056f, 0.012f, (rodTop - 0.056f).coerceAtLeast(0f), Palette.STEEL_R, Palette.STEEL_G, Palette.STEEL_B, 0.95f)
        // Head.
        batch.rect(laneCx - 0.010f, rodTop, 0.020f, 0.006f, 0.9f, 0.95f, 1f, 1f)
        batch.flush(shader)

        additiveBlend()
        batch.begin()
        // Compression glow.
        batch.rect(laneCx - 0.013f, 0.056f, 0.026f, (rodTop - 0.050f).coerceAtLeast(0f) + 0.010f, Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, pull * 0.45f * fxScale)
        batch.flush(shader)
    }

    private fun drawBalls() {
        for (ball in sim.balls) {
            if (!ball.body.isActive) continue
            val x = ball.renderX(alpha)
            val y = ball.renderY(alpha)
            val r = TableTuning.BALL_R

            // Trail.
            additiveBlend()
            batch.begin()
            val trail = trailFor(ball.id)
            val n = 10
            for (i in 1..n) {
                val age = i * 2
                val tx = trail.xAt(age)
                val ty = trail.yAt(age)
                val dx = x - tx
                val dy = y - ty
                val d2 = dx * dx + dy * dy
                if (d2 < 1e-8f) continue
                val speedFactor = min(1f, d2 * 55f)
                val a = (1f - i.toFloat() / n) * 0.38f * speedFactor * fxScale
                if (a < 0.01f) continue
                batch.circle(tx, ty, r * (1f - i.toFloat() / (n + 2f)), Palette.CYAN_R, Palette.CYAN_G, Palette.CYAN_B, a, 12)
            }
            batch.flush(shader)

            // Ball shadow.
            normalBlend()
            batch.begin()
            batch.circle(x - r * 0.35f, y - r * 0.45f, r * 1.02f, 0f, 0f, 0.02f, 0.5f, 14)
            batch.flush(shader)

            // Body: layered circles to fake a metallic sphere.
            batch.begin()
            batch.circle(x, y, r, 0.42f, 0.47f, 0.55f, 1f, 18)
            batch.circle(x - r * 0.12f, y + r * 0.10f, r * 0.86f, 0.68f, 0.73f, 0.82f, 1f, 16)
            batch.circle(x - r * 0.22f, y + r * 0.24f, r * 0.62f, 0.86f, 0.90f, 0.96f, 1f, 14)
            batch.circle(x - r * 0.34f, y + r * 0.38f, r * 0.30f, 1f, 1f, 1f, 0.95f, 10)
            // Rim light.
            additiveBlend()
            batch.flush(shader)
            batch.begin()
            batch.ring(x, y, r * 0.86f, r, 0.75f, 0.92f, 1f, 0.35f * fxScale, 16)
            batch.flush(shader)
        }
    }

    private val trails = HashMap<Int, BallTrail>()

    private fun trailFor(id: Int): BallTrail {
        var t = trails[id]
        if (t == null) {
            t = BallTrail(26)
            t.reset(0f, 0f, 0f)
            trails[id] = t
        }
        return t
    }

    /** Called from the game loop callback to feed trails. */
    fun updateTrails() {
        for (ball in sim.balls) {
            if (!ball.body.isActive) continue
            val t = trailFor(ball.id)
            t.push(ball.currX, ball.currY, timeS)
        }
    }

    private fun drawParticles() {
        additiveBlend()
        batch.begin()
        for (p in particles.particles) {
            if (!p.alive) continue
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            val a = k * k * 0.85f
            batch.circle(p.x, p.y, p.size * (0.6f + 0.4f * k), p.r, p.g, p.b, a, 8)
            batch.circle(p.x, p.y, p.size * 1.8f * k + 0.0012f, p.r, p.g, p.b, a * 0.25f, 8)
        }
        batch.flush(shader)
    }

    private fun drawPopups() {
        normalBlend()
        batch.begin()
        for (p in popups) {
            if (p.life <= 0f) continue
            val k = (p.life / 0.9f).coerceIn(0f, 1f)
            val rise = (1f - k) * 0.030f
            val r = if (p.gold) Palette.GOLD_R else 1f
            val g = if (p.gold) Palette.GOLD_G else 0.95f
            val b = if (p.gold) Palette.GOLD_B else 0.75f
            // Diamond marker (score value text is drawn by the HUD overlay).
            val s = 0.008f * p.size
            val y = p.y + rise
            batch.tri(p.x, y + s, p.x - s * 0.6f, y, p.x + s * 0.6f, y, r, g, b, k)
            batch.tri(p.x, y - s, p.x - s * 0.6f, y, p.x + s * 0.6f, y, r, g, b, k)
        }
        batch.flush(shader)
    }

    private fun drawVignette() {
        normalBlend()
        batch.begin()
        val w = camera.worldRight - camera.worldLeft
        val h = camera.worldTop - camera.worldBottom
        val edge = 0.030f
        batch.rect(camera.worldLeft, camera.worldBottom, w, edge, 0f, 0f, 0f, 0.5f)
        batch.rect(camera.worldLeft, camera.worldTop - edge, w, edge, 0f, 0f, 0f, 0.5f)
        batch.rect(camera.worldLeft, camera.worldBottom, edge, h, 0f, 0f, 0f, 0.5f)
        batch.rect(camera.worldRight - edge, camera.worldBottom, edge, h, 0f, 0f, 0f, 0.5f)
        batch.flush(shader)

        if (flash > 0f) {
            additiveBlend()
            batch.begin()
            batch.rect(camera.worldLeft, camera.worldBottom, w, h, 1f, 1f, 1f, flash * 0.55f)
            batch.flush(shader)
        }
    }
}
