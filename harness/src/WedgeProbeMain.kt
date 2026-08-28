package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.TableTuning

/**
 * Drops balls on a grid over the lower-left lane area with real physics and
 * no input, then reports every start that ends neither drained nor moving:
 * those are wedge points in the geometry.
 */
object WedgeProbeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val dt = TableTuning.FIXED_DT
        if (args.size >= 2) {
            trace(args[0].toFloat(), args[1].toFloat(), dt)
            return
        }
        var wedges = 0
        var drained = 0
        var roaming = 0

        println("== lower-left wedge probe (8 s per drop, no input)")
        var y = 0.30f
        while (y >= 0.08f - 1e-4f) {
            val row = StringBuilder()
            var x = 0.050f
            while (x <= 0.150f + 1e-4f) {
                val sim = GameSim()
                sim.ballSearchEnabled = false
                val id = sim.debugSpawnBall(x, y)
                val ball = sim.ballById(id)!!
                var t = 0f
                var maxV = 0f
                while (t < 8f) {
                    sim.update(dt)
                    if (t < 0.5f) maxV = maxOf(maxV, ball.body.linearVelocity.length())
                    t += dt
                }
                // Spawned inside or behind geometry: depenetration explosion,
                // not a reachable resting state.
                if (maxV > 3.5f) {
                    row.append('x')
                    x += 0.005f
                    continue
                }
                val p = ball.body.position
                val v = ball.body.linearVelocity.length()
                val out = !ball.body.isActive
                row.append(
                    when {
                        out -> { drained++; '.' }
                        v < 0.03f -> { wedges++; 'W' }
                        else -> { roaming++; '~' }
                    },
                )
                if (row.last() == 'W') {
                    println("   WEDGE start=(${"%.3f".format(x)}, ${"%.2f".format(y)}) rest=(${"%.3f".format(p.x)}, ${"%.3f".format(p.y)}) speed=${"%.3f".format(v)}")
                }
                x += 0.005f
            }
            println("   y=%4.2f  %s".format(y, row.toString()))
            y -= 0.02f
        }
        println("== wedges=$wedges drained=$drained roaming=$roaming")
    }

    private fun trace(x: Float, y: Float, dt: Float) {
        val sim = GameSim()
        sim.ballSearchEnabled = false
        val id = sim.debugSpawnBall(x, y)
        val ball = sim.ballById(id)!!
        var t = 0f
        var next = 0f
        while (t < 6f) {
            sim.update(dt)
            t += dt
            if (t >= next) {
                val p = ball.body.position
                println(
                    "   t=%4.2f pos=(%.3f, %.3f) v=%.3f active=%s".format(
                        t, p.x, p.y, ball.body.linearVelocity.length(), ball.body.isActive,
                    ),
                )
                next += 0.25f
            }
        }
    }
}
