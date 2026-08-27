package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.Ids
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Multiball choreography E2E against the real sim + session:
 * light the lock via real rollover events, "shoot the ramp" by invoking the
 * capture path (physics feeding the scoop is covered by sim tests), verify
 * parking, the MB eject sequence, auto-plunge, jackpot capture and drain-out.
 */
object MultiballTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sim = GameSim()
        var failures = 0
        fun check(cond: Boolean, msg: String) {
            if (!cond) {
                failures++
                println("  FAIL: $msg")
            } else {
                println("  ok: $msg")
            }
        }

        val session = GameSession(sim, object : GameSession.Listener {})
        sim.onEvent = { session.handleEvent(it) }
        val loop = com.superheroghost.neonpinball.game.GameLoop(sim)
        loop.start()
        loop.onFixedStep = { session.onBallDrained() }

        fun step(seconds: Float) {
            var t = 0f
            while (t < seconds) {
                session.update(1f / 60f)
                loop.update(1f / 60f)
                t += 1f / 60f
            }
        }
        fun fire(e: SimEvent) {
            // Rules-only events (as if the ball physically crossed sensors).
            session.handleEvent(e)
        }

        session.startGame()
        step(0.5f)

        // Launch: yank the plunger.
        sim.setPlungerPull(1f)
        sim.setPlungerHeld(false)
        step(3.0f)
        check(sim.anyLiveBall(), "ball launched and live")

        // Light lock: lanes + standups (2 credits).
        fire(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        fire(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        fire(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        fire(SimEvent.StandupHit(Ids.STANDUP_L, 0f, 0f))
        fire(SimEvent.StandupHit(Ids.STANDUP_R, 0f, 0f))
        check(session.rules.lockLit, "lock lit")

        // Ramp shot: the ball rides the ramp and reaches the scoop. Emulate
        // the physical arrival: the sim's scoop capture path for a live ball.
        val live = sim.balls.first { it.body.isActive }
        sim.captureBall(live.id)
        fire(SimEvent.HoleCapture(live.id, 0))
        check(session.rules.ballsLocked == 1, "one ball locked (got ${session.rules.ballsLocked})")
        check(sim.parked.contains(live.id), "ball parked in sim")
        step(1.5f)
        check(sim.liveBallCount() >= 1 || sim.plungerOccupied(), "replacement ball served after lock (live=${sim.liveBallCount()} plunger=${sim.plungerOccupied()})")

        // Relight and lock the second.
        fire(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        fire(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        fire(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        fire(SimEvent.StandupHit(Ids.STANDUP_L, 0f, 0f))
        fire(SimEvent.StandupHit(Ids.STANDUP_R, 0f, 0f))
        check(session.rules.lockLit, "lock relit")
        val live2 = sim.balls.first { it.body.isActive }
        sim.captureBall(live2.id)
        fire(SimEvent.HoleCapture(live2.id, 0))
        check(session.rules.ballsLocked == 2, "two locked")
        check(session.rules.multiballActive, "multiball active")

        // Let the MB choreography run: two ejects + auto-plunge.
        for (i in 0 until 12) {
            step(0.5f)
                    }
        check(sim.parked.isEmpty(), "all parked balls ejected (parked=${sim.parked})")
        check(sim.liveBallCount() >= 2, "multiball live (live=${sim.liveBallCount()})")

        // Jackpot: capture a live ball during MB.
        val jball = sim.balls.first { it.body.isActive }
        sim.captureBall(jball.id)
        fire(SimEvent.HoleCapture(jball.id, 0))
        check(session.rules.jackpotsCollected == 1, "jackpot collected (got ${session.rules.jackpotsCollected})")
        step(2.0f)
        check(sim.liveBallCount() >= 1, "ball ejected after jackpot (live=${sim.liveBallCount()})")

        // Drain everything; MB should end and the game should continue cleanly.
        while (sim.liveBallCount() > 0 && session.phase == com.superheroghost.neonpinball.game.GameSession.Phase.PLAYING) {
            val b = sim.balls.first { it.body.isActive }
            b.place(b.body.position.x, 0.01f, 0f, 0f)
            step(0.5f)
        }
        step(2.5f)
        check(session.phase != com.superheroghost.neonpinball.game.GameSession.Phase.PLAYING || sim.liveBallCount() > 0,
            "game advanced after MB drain (phase=${session.phase})")
        check(sim.escapedBalls == 0, "no escapes")

        println(if (failures == 0) "MULTIBALL E2E PASS" else "MULTIBALL E2E FAIL ($failures)")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
