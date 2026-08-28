package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.game.GameLoop
import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.game.InputState
import com.superheroghost.neonpinball.sim.GameSim
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency soak for the new-game request path: one thread steps the
 * simulation exactly like the GL thread does, while another hammers
 * requestNewGame() and reads session state like the UI thread does. The old
 * crash came from the UI thread mutating the session mid-step; the fix queues
 * restarts and consumes them on the sim thread. Any throwable fails the run.
 */
object SoakMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = if (args.isNotEmpty()) args[0].toFloat() else 20f
        val sim = GameSim()
        val session = GameSession(sim, object : GameSession.Listener {})
        val loop = GameLoop(sim)
        val input = InputState()
        loop.input = input
        loop.start()
        loop.onFixedStep = { session.onBallDrained() }
        sim.onEvent = { session.handleEvent(it) }
        session.startGame()

        val failed = AtomicReference<Throwable?>(null)
        val done = AtomicBoolean(false)
        var frames = 0

        val glThread = Thread {
            try {
                val dt = 1f / 60f
                while (!done.get()) {
                    session.update(dt)
                    loop.update(dt)
                    frames++
                    if (frames % 300 == 0) {
                        input.leftFlipper = !input.leftFlipper
                        input.rightFlipper = !input.rightFlipper
                    }
                    if (frames % 90 == 0) {
                        input.plungerHeld = !input.plungerHeld
                    }
                }
            } catch (t: Throwable) {
                failed.set(t)
            }
        }
        glThread.name = "soak-gl"
        glThread.start()

        val deadline = System.nanoTime() + (seconds.toLong() * 1_000_000_000L)
        var restarts = 0L
        while (System.nanoTime() < deadline && failed.get() == null) {
            session.requestNewGame()
            restarts++
            // Reads, the way the HUD/controller touch the session off-thread.
            session.rules.score
            session.phase
            Thread.sleep(2)
        }
        done.set(true)
        glThread.join(2000)

        val t = failed.get()
        if (t != null) {
            println("SOAK FAIL after $frames frames / $restarts restarts")
            t.printStackTrace(System.out)
            kotlin.system.exitProcess(1)
        }
        println(
            "SOAK PASS frames=$frames (${frames / 60}s sim) restarts=$restarts " +
                "phase=${session.phase} score=${session.rules.score} escaped=${sim.escapedBalls}",
        )
    }
}
