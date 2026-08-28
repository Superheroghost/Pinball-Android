package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.sim.GameSim

/** Traces a plunge shot: prints ball position/velocity every few steps. */
object LaunchTraceMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val pull = if (args.isNotEmpty()) args[0].toFloat() else 1.0f
        val sim = GameSim()
        sim.onEvent = { e -> println("EVENT $e") }
        sim.serveBall(0)
        sim.setPlungerPull(pull)
        sim.setPlungerHeld(false)
        val dt = 1f / 60f
        for (i in 0 until 60 * 12) {
            sim.update(dt)
            if (i % 5 == 0) {
                val b = sim.ballById(0) ?: break
                if (!b.body.isActive && i > 60) break
                val p = b.body.position
                val v = b.body.linearVelocity
                println(
                    "step=%3d pos=(%.4f,%.4f) v=(%.2f,%.2f) state=%s".format(
                        i, p.x, p.y, v.x, v.y, b.state,
                    ),
                )
            }
        }
    }
}
