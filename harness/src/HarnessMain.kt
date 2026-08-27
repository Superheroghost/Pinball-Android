package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.sim.BallState
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Physics smoke test: serves a ball, plunges at various strengths, and reports
 * what happens. Used to validate the table geometry and tuning.
 */
object HarnessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Neon Nexus Pinball — sim harness ===")
        for (pull in floatArrayOf(1.0f, 0.62f, 0.35f)) {
            runLaunch(pull)
        }
        runFlipperTest()
    }

    private fun collect(sim: GameSim): StringBuilder {
        val log = StringBuilder()
        sim.onEvent = { e ->
            when (e) {
                is SimEvent.BallLaunched -> log.append("LAUNCHED(${e.ball},v=${"%.2f".format(e.speed)}) ")
                is SimEvent.GatePassed -> log.append("GATE ")
                is SimEvent.Rollover -> log.append("ROLL${e.lane} ")
                is SimEvent.ShotLane -> log.append("SHOT${e.id} ")
                is SimEvent.HoleCapture -> log.append("CAPTURE ")
                is SimEvent.BallDrained -> log.append("DRAIN ")
                is SimEvent.BumperHit -> log.append("BUMP ")
                is SimEvent.SlingHit -> log.append("SLING ")
                is SimEvent.SpinnerSpins -> log.append("SPIN(${e.revs}) ")
                is SimEvent.FlipperHit -> log.append("FLIP ")
                is SimEvent.DropTargetDown -> log.append("DROP ")
                is SimEvent.DropBankComplete -> log.append("BANKRESET ")
                is SimEvent.StandupHit -> log.append("STANDUP ")
                else -> {}
            }
        }
        return log
    }

    private fun runLaunch(pull: Float) {
        val sim = GameSim()
        val log = collect(sim)
        sim.serveBall(0)
        sim.setPlungerPull(pull)
        sim.setPlungerHeld(false)

        var maxY = 0f
        var enteredPlayfield = false
        val dt = 1f / 60f
        var steps = 0
        while (steps < 60 * 30) {
            sim.update(dt)
            val b = sim.ballById(0)!!
            if (b.body.isActive) {
                if (b.currY > maxY) maxY = b.currY
                if (b.state == BallState.LIVE || b.state == BallState.ON_RAMP) enteredPlayfield = true
            }
            if (!sim.anyLiveBall() && !sim.balls[0].body.isActive && sim.balls[0].state != BallState.ON_PLUNGER) break
            steps++
        }
        println(
            "pull=$pull -> maxY=%.3f playfield=$enteredPlayfield steps=$steps nudges=${sim.stuckNudges} escaped=${sim.escapedBalls}".format(maxY),
        )
        println("   events: $log")
    }

    private fun runFlipperTest() {
        val sim = GameSim()
        var flipHits = 0
        sim.onEvent = { e -> if (e is SimEvent.FlipperHit) flipHits++ }
        // Drop a ball onto the left flipper and flip it.
        val id = sim.debugSpawnBall(0.18f, 0.30f)
        var flipped = false
        var launchSpeed = 0f
        val dt = 1f / 60f
        for (i in 0 until 60 * 12) {
            sim.update(dt)
            val b = sim.ballById(id)!!
            if (!flipped && b.currY < 0.175f) {
                sim.table.flipperL.setPressed(true)
                flipped = true
            }
            if (flipped && b.speed > launchSpeed) launchSpeed = b.speed
            if (flipped && i % 240 == 0 && sim.table.flipperL.isPressed) sim.table.flipperL.setPressed(false)
            if (!sim.anyLiveBall()) break
        }
        println("flipper test: hits=$flipHits maxSpeed=%.2f nudges=${sim.stuckNudges}".format(launchSpeed))
    }
}
