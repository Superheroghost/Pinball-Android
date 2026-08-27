package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.game.GameLoop
import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.game.InputState
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * End-to-end game test: a scripted "player" plays a full 3-ball game through
 * the real GameLoop, GameSession, InputState and GameSim physics. Verifies:
 *  - ball flow 1..3 and GAME_OVER
 *  - score accumulates from real physics events
 *  - no ball escapes, no crash, deterministic frame budget
 */
object SessionTestMain {
    class Recorder : GameSession.Listener {
        val messages = ArrayList<String>()
        var lastScore = 0L
        var lastBall = 1
        var gameOverScore = -1L
        override fun onScoreChanged(score: Long) {
            lastScore = score
        }

        override fun onBallChanged(ball: Int) {
            lastBall = ball
        }

        override fun onMessage(text: String, duration: Float) {
            messages.add(text)
        }

        override fun onGameOver(finalScore: Long) {
            gameOverScore = finalScore
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val sim = GameSim()
        val rec = Recorder()
        val session = GameSession(sim, rec)
        val input = InputState()
        val loop = GameLoop(sim)
        loop.input = input
        loop.start()
        loop.onFixedStep = {
            // Drain watcher: the renderer does this per-frame in the app.
            session.onBallDrained()
        }
        // Wire events into the session like GameController does.
        var nLaunch = 0
        var nGate = 0
        var nDrain = 0
        sim.onEvent = { e ->
            when (e) {
                is SimEvent.BallLaunched -> nLaunch++
                is SimEvent.GatePassed -> nGate++
                is SimEvent.BallDrained -> nDrain++
                else -> {}
            }
            session.handleEvent(e)
        }

        session.startGame()

        val dt = 1f / 60f
        var frames = 0
        val maxFrames = 60 * 240 // 4 minutes of play max
        var rng = 123456789L
        fun rand(): Float {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            return ((rng ushr 33).toFloat() / (1L shl 31).toFloat())
        }

        var nextAction = 60
        while (frames < maxFrames) {
            // Scripted player: plunge when a ball sits on the plunger, flip
            // randomly, release plunger after pulls.
            if (frames == nextAction) {
                val what = rand()
                when {
                    what < 0.35f -> {
                        input.plungerHeld = true
                        input.plungerPull = 0.65f + rand() * 0.35f
                    }
                    what < 0.6f -> {
                        input.plungerHeld = false
                        input.leftFlipper = rand() < 0.7f
                        input.rightFlipper = rand() < 0.7f
                    }
                    else -> {
                        input.leftFlipper = rand() < 0.5f
                        input.rightFlipper = rand() < 0.5f
                    }
                }
                nextAction = frames + 8 + (rand() * 30).toInt()
            }

            session.update(dt)
            loop.update(dt)
            frames++
            if (frames % 3600 == 0) {
                val b = sim.ballById(0)
                println("t=${frames / 60}s live=${sim.liveBallCount()} ball=${b?.state} y=${b?.body?.position?.y} pull=${sim.plungerPull} phase=${session.phase}")
            }
            if (session.phase == GameSession.Phase.GAME_OVER) break
        }

        println("=== session e2e ===")
        println("frames=${frames} (${frames / 60}s) phase=${session.phase}")
        println("finalBall=${rec.lastBall} score=${session.rules.score}")
        println("gameOverScore=${rec.gameOverScore}")
        println("escapedBalls=${sim.escapedBalls}")
        println("messages=${rec.messages.take(12)}")
        val ok = session.phase == GameSession.Phase.GAME_OVER &&
            rec.gameOverScore == session.rules.score &&
            session.rules.score > 0 &&
            sim.escapedBalls == 0
        println(if (ok) "SESSION E2E PASS" else "SESSION E2E FAIL")
        if (!ok) kotlin.system.exitProcess(1)
    }
}
