package com.superheroghost.neonpinball.game

import com.superheroghost.neonpinball.sim.TableTuning
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pooled additive particle system. Particles are simple glowing quads with
 * radial falloff faked by layered alpha; rendered in one batch.
 */
class ParticleSystem(private val max: Int = 1400) {
    class P {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var maxLife = 1f
        var size = 0.004f
        var r = 1f
        var g = 1f
        var b = 1f
        var gravity = 0f
        var drag = 2.2f
        var alive = false
    }

    val particles = Array(max) { P() }
    private var cursor = 0
    var activeCount = 0
        private set

    fun spawn(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        life: Float,
        size: Float,
        r: Float,
        g: Float,
        b: Float,
        gravityScale: Float = 1f,
        drag: Float = 2.2f,
    ) {
        // Find a free particle starting at the cursor (ring buffer).
        for (i in 0 until max) {
            val p = particles[(cursor + i) % max]
            if (!p.alive) {
                cursor = (cursor + i + 1) % max
                p.x = x
                p.y = y
                p.vx = vx
                p.vy = vy
                p.life = life
                p.maxLife = life
                p.size = size
                p.r = r
                p.g = g
                p.b = b
                p.gravity = gravityScale * TableTuning.GRAVITY
                p.drag = drag
                p.alive = true
                activeCount++
                return
            }
        }
    }

    /** Radial burst helper. */
    fun burst(x: Float, y: Float, count: Int, speedMin: Float, speedMax: Float, life: Float, size: Float, r: Float, g: Float, b: Float, biasX: Float = 0f, biasY: Float = 0f) {
        for (i in 0 until count) {
            val a = (Math.random() * Math.PI * 2).toFloat()
            val sp = speedMin + (Math.random() * (speedMax - speedMin)).toFloat()
            spawn(
                x, y,
                cos(a) * sp + biasX,
                sin(a) * sp + biasY,
                life * (0.6f + Math.random() * 0.4f).toFloat(),
                size * (0.6f + Math.random() * 0.8f).toFloat(),
                r, g, b,
            )
        }
    }

    fun update(dt: Float) {
        for (i in 0 until max) {
            val p = particles[i]
            if (!p.alive) continue
            p.life -= dt
            if (p.life <= 0f) {
                p.alive = false
                activeCount--
                continue
            }
            p.vy -= p.gravity * dt
            val d = 1f - p.drag * dt
            p.vx *= d.coerceAtLeast(0f)
            p.vy *= d.coerceAtLeast(0f)
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    fun clear() {
        for (i in 0 until max) {
            particles[i].alive = false
        }
        activeCount = 0
    }
}

/**
 * Ball trail: ring buffer of past positions with per-sample brightness.
 */
class BallTrail(val samples: Int = 26) {
    val xs = FloatArray(samples)
    val ys = FloatArray(samples)
    val ts = FloatArray(samples)
    private var index = 0

    fun reset(x: Float, y: Float, t: Float) {
        for (i in 0 until samples) {
            xs[i] = x
            ys[i] = y
            ts[i] = t
        }
        index = 0
    }

    fun push(x: Float, y: Float, t: Float) {
        index = (index + 1) % samples
        xs[index] = x
        ys[index] = y
        ts[index] = t
    }

    /** Sample age 0 (newest) .. samples-1 (oldest). */
    fun xAt(age: Int): Float = xs[(index - age + samples * 2) % samples]
    fun yAt(age: Int): Float = ys[(index - age + samples * 2) % samples]
    fun tAt(age: Int): Float = ts[(index - age + samples * 2) % samples]
}
