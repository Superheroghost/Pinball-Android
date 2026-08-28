package com.superheroghost.neonpinball.game

import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.RuleEvent
import com.superheroghost.neonpinball.sim.RulesEngine
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Ball-flow orchestration on top of [RulesEngine]: game/ball/drain transitions,
 * multiball eject choreography, extra balls. The session owns timing-sensitive
 * sequencing (delays) that rules deliberately keep stateless.
 */
class GameSession(
    private val sim: GameSim,
    private val listener: Listener,
) {
    interface Listener {
        fun onScoreChanged(score: Long) {}
        fun onBallChanged(ball: Int) {}
        fun onMessage(text: String, duration: Float) {}
        fun onGameOver(finalScore: Long) {}
        fun onFxEvent(event: SimEvent) {}
    }

    enum class Phase { ATTRACT, PLAYING, BALL_END, GAME_OVER }

    var phase = Phase.ATTRACT
        private set
    var ballNumber = 1
        private set
    val totalBalls = 3

    val rules = RulesEngine(sim, this::onRuleEvent)

    private var ballEndTimer = 0f
    private var pendingServes = 0
    private var extraBallsPending = 0
    private class Delayed(var time: Float, val kind: Int, val action: () -> Unit)
    private val delayed = ArrayList<Delayed>()

    companion object {
        private const val KIND_SERVE = 0
        private const val KIND_MB = 1
    }

    // Multiball bookkeeping.
    private var mbEjectPending = false

    fun isPlaying() = phase == Phase.PLAYING

    /**
     * Thread-safe new-game request from the UI. The sim world must only ever
     * be mutated on the thread that steps it (the GL thread), so the request
     * is consumed at the top of [update] instead of resetting bodies here.
     */
    @Volatile
    private var newGameRequested = false

    fun requestNewGame() {
        newGameRequested = true
    }

    fun startGame() {
        sim.resetAll()
        rules.newGame()
        ballNumber = 1
        extraBallsPending = 0
        pendingServes = 0
        mbEjectPending = false
        phase = Phase.PLAYING
        listener.onScoreChanged(rules.score)
        listener.onBallChanged(ballNumber)
        listener.onMessage("BALL 1", 1.2f)
        // resetAll() already serves ball 0.
    }

    // ------------------------------------------------------------ ticking

    fun update(dt: Float) {
        if (newGameRequested) {
            newGameRequested = false
            startGame()
        }

        // Run delayed actions.
        if (delayed.isNotEmpty()) {
            val it = delayed.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.time <= dt) {
                    it.remove()
                    p.action()
                } else {
                    p.time -= dt
                }
            }
        }

        rules.update(dt)

        when (phase) {
            Phase.BALL_END -> {
                ballEndTimer -= dt
                if (ballEndTimer <= 0f) {
                    if (extraBallsPending > 0) {
                        extraBallsPending--
                        startSameBall()
                    } else if (ballNumber < totalBalls) {
                        ballNumber++
                        startNextBall()
                    } else {
                        phase = Phase.GAME_OVER
                        listener.onGameOver(rules.score)
                    }
                }
            }
            else -> {}
        }

        // Multiball eject sequencing.
        if (mbEjectPending && phase == Phase.PLAYING) {
            mbEjectPending = false
            scheduleMultiballEject()
        }
    }

    private fun runDelayed(time: Float, kind: Int = KIND_MB, action: () -> Unit) {
        delayed.add(Delayed(time, kind, action))
    }

    /** Cancel any pending plunger serves (e.g. when multiball takes over). */
    private fun cancelPendingServes() {
        delayed.removeAll { it.kind == KIND_SERVE }
    }

    // ------------------------------------------------------------ flow

    private fun startNextBall() {
        rules.resetForBall()
        listener.onBallChanged(ballNumber)
        listener.onMessage("BALL $ballNumber", 1.2f)
        sim.serveFreeBall()
    }

    private fun startSameBall() {
        rules.resetForBall()
        listener.onMessage("EXTRA BALL", 1.6f)
        sim.serveFreeBall()
    }

    // ------------------------------------------------------------ events

    /** Sim events (called from the sim event queue pump). */
    fun handleEvent(event: SimEvent) {
        listener.onFxEvent(event)
        rules.onSimEvent(event)
        if (event is SimEvent.HoleEject) {
            rules.onScoopEjected()
        }
        if (rules.score != lastReportedScore) {
            lastReportedScore = rules.score
            listener.onScoreChanged(rules.score)
        }
    }

    private var lastReportedScore = -1L

    private fun onRuleEvent(event: RuleEvent) {
        when (event) {
            is RuleEvent.SkillShotLit -> listener.onMessage("SKILL SHOT", 1.5f)
            is RuleEvent.SkillShotMade -> listener.onMessage("SKILL SHOT +${event.points / 1000}K", 1.5f)
            is RuleEvent.SkillShotMissed -> {}
            is RuleEvent.BonusMultiplierUp -> listener.onMessage("BONUS ${event.newMultiplier}X", 1.4f)
            is RuleEvent.LaneComplete -> {}
            is RuleEvent.LockLit -> listener.onMessage("LOCK LIT", 1.6f)
            is RuleEvent.BallLocked -> {
                if (event.lockedCount < 2) {
                    listener.onMessage("BALL LOCKED", 1.4f)
                    // Keep play moving: serve a replacement ball.
                    if (sim.liveBallCount() == 0) {
                        runDelayed(1.0f, KIND_SERVE) {
                            if (phase == Phase.PLAYING && !sim.plungerOccupied()) sim.serveFreeBall()
                        }
                    }
                } else {
                    listener.onMessage("LOCKED ${event.lockedCount}/2", 1.4f)
                }
            }
            is RuleEvent.MultiballReady -> {
                listener.onMessage("MULTIBALL READY", 1.6f)
                cancelPendingServes()
                mbEjectPending = true
            }
            is RuleEvent.MultiballStart -> listener.onMessage("MULTIBALL!", 2.0f)
            is RuleEvent.MultiballEnd -> listener.onMessage("MULTIBALL OVER", 1.6f)
            is RuleEvent.Jackpot -> listener.onMessage("JACKPOT +${event.points / 1000}K", 1.8f)
            is RuleEvent.SuperJackpotLit -> listener.onMessage("SUPER JACKPOT LIT — SHOOT ORBIT", 2.2f)
            is RuleEvent.SuperJackpot -> listener.onMessage("SUPER JACKPOT!", 2.4f)
            is RuleEvent.ExtraBallLit -> listener.onMessage("EXTRA BALL LIT — SHOOT SCOOP", 2.0f)
            is RuleEvent.ExtraBallAwarded -> {
                extraBallsPending++
                listener.onMessage("EXTRA BALL", 2.0f)
            }
            is RuleEvent.OverdriveStart -> listener.onMessage("OVERDRIVE! ALL SCORES 2X", 2.4f)
            is RuleEvent.OverdriveEnd -> listener.onMessage("OVERDRIVE OVER", 1.4f)
            is RuleEvent.ObjectiveComplete -> listener.onMessage("${RulesEngine.OBJECTIVE_NAMES[event.objective]} COMPLETE", 1.2f)
            is RuleEvent.BallSaveStart -> {}
            is RuleEvent.BallSaveUsed -> listener.onMessage("BALL SAVED", 1.6f)
            is RuleEvent.BallSaveExpired -> {}
            is RuleEvent.Combo -> listener.onMessage("COMBO x${event.count}", 1.0f)
            is RuleEvent.ScoreMultChange -> {}
            is RuleEvent.BonusTally -> {
                listener.onMessage("BONUS ${event.base} x ${event.multiplier} = ${event.total}", 2.6f)
            }
        }
    }

    /** Called by the sim-drain watcher (renderer sees drain via events). */
    fun onBallDrained() {
        if (phase == Phase.PLAYING && sim.liveBallCount() == 0 && !rules.playing) {
            phase = Phase.BALL_END
            ballEndTimer = 1.4f
            listener.onMessage("BALL ${ballNumber} OVER", 1.4f)
        }
    }

    private fun scheduleMultiballEject() {
        val parked = ArrayList(sim.parked)
        var delay = 1.2f
        for (id in parked) {
            runDelayed(delay) {
                if (phase == Phase.PLAYING) {
                    sim.ejectFromScoop(id)
                }
            }
            delay += 0.7f
        }
        // Auto-plunge a third ball so multiball plays at full strength even
        // though both locks captured the balls in play.
        runDelayed(delay + 0.3f, KIND_SERVE) {
            if (phase == Phase.PLAYING && !rules.holdingBall && !sim.plungerOccupied()) {
                if (sim.serveFreeBall() >= 0) {
                    sim.setPlungerPull(1f)
                    sim.setPlungerHeld(false)
                }
            }
        }
        rules.beginMultiballEject()
    }
}
