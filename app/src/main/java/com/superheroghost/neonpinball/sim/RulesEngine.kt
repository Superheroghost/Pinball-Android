package com.superheroghost.neonpinball.sim

import kotlin.math.min

/**
 * The pinball ruleset. Pure Kotlin: consumes [SimEvent]s, tracks progression
 * and scoring, and emits [RuleEvent]s + light states. All timing comes from
 * [update] so it is fully deterministic and unit-testable.
 *
 * Progression summary
 * ---
 *  N-E-X top lanes    complete -> bonus multiplier up (2x..6x) + 1 lock credit
 *  Standups L+R       both hit -> 1 lock credit (per completion)
 *  Drop bank          complete -> 1 lock credit; 2 total banks -> Extra Ball lit
 *  LOCK LIT when 2 credits; ramp shot captures & locks the ball
 *  2 balls locked     -> MULTIBALL (both eject from the scoop)
 *  Ramp during MB     -> JACKPOT (3 to light SUPER at the orbit)
 *  Orbit apex (SUPER) -> SUPER JACKPOT
 *  Combo chain        -> shots within 2.5s build combo scoring
 *  Objectives (6)     -> ORBIT RAMP DROPS STANDUPS SPIN BUMPERS; all in one
 *                        ball -> OVERDRIVE (20s, 2x scoring)
 *  Ball save          8s from launch; re-serves a drained ball
 *  Bonus at ball end  = weighted stats x bonus multiplier
 */
class RulesEngine(
    private val actions: SimActions,
    private val listener: RuleEventListener,
) {
    companion object {
        const val MAX_BONUS_MULTIPLIER = 6
        const val BALL_SAVE_TIME = 8f
        const val SKILL_SHOT_TIME = 4f
        const val COMBO_WINDOW = 2.5f
        const val OVERDRIVE_TIME = 20f
        const val JACKPOTS_FOR_SUPER = 3

        // Objective ids (also light ids).
        const val OBJ_ORBIT = 0
        const val OBJ_RAMP = 1
        const val OBJ_DROPS = 2
        const val OBJ_STANDUPS = 3
        const val OBJ_SPINNER = 4
        const val OBJ_BUMPERS = 5

        val OBJECTIVE_NAMES = arrayOf("ORBIT", "RAMP", "BANK", "TARGETS", "SPIN", "BUMPER")
    }

    // ------------------------------------------------------------ state
    var score = 0L
        private set
    var scoreMultiplier = 1
        private set
    var bonusMultiplier = 1
        private set

    /** Lane progress: N, E, X. */
    val lanesDone = BooleanArray(3)

    /** Standup progress. */
    val standupsDone = BooleanArray(2)

    /** Drop bank completions this ball. */
    var banksCompleted = 0
        private set

    /** Lock credits toward lighting lock. */
    private var lockCredits = 0
    var lockLit = false
        private set
    var ballsLocked = 0
        private set

    var multiballActive = false
        private set
    var jackpotsCollected = 0
        private set
    var superJackpotLit = false
        private set
    var superJackpots = 0
        private set

    var extraBallLit = false
        private set
    var extraBallsWon = 0
        private set

    /** Objectives this ball. */
    val objectivesDone = BooleanArray(6)
    var overdriveTimeLeft = 0f
        private set

    /** Ball-save. */
    var ballSaveTimeLeft = 0f
        private set

    /** Skill shot. */
    var skillShotTimeLeft = 0f
        private set
    var skillShotLane = -1
        private set

    /** Combo. */
    var comboCount = 0
        private set
    private var comboTimer = 0f
    var bestCombo = 0
        private set

    /** Ball-end bonus stats. */
    var statBumperHits = 0
    var statLanesComplete = 0
    var statOrbits = 0
    var statRampShots = 0
    var statSpinnerRevs = 0
    var statDropTargets = 0
    var statStandupHits = 0
        private set

    /** Whether a ball is currently being held in the scoop (lock animation). */
    var holdingBall = false
        private set

    /** Per-ball active flag; rules only tick while playing. */
    var playing = false
        private set

    /** Total balls drained this ball (for ball save logic). */
    private var launchedThisBall = false

    // ------------------------------------------------------------ lifecycle

    fun newGame() {
        score = 0
        scoreMultiplier = 1
        resetForBall()
    }

    fun resetForBall() {
        lanesDone.fill(false)
        standupsDone.fill(false)
        banksCompleted = 0
        lockCredits = 0
        lockLit = false
        // Locks persist across balls until multiball starts.
        jackpotsCollected = 0
        superJackpotLit = false
        extraBallLit = false
        objectivesDone.fill(false)
        overdriveTimeLeft = 0f
        ballSaveTimeLeft = 0f
        skillShotTimeLeft = 0f
        skillShotLane = -1
        comboCount = 0
        comboTimer = 0f
        statBumperHits = 0
        statLanesComplete = 0
        statOrbits = 0
        statRampShots = 0
        statSpinnerRevs = 0
        statDropTargets = 0
        statStandupHits = 0
        holdingBall = false
        launchedThisBall = false
        multiballActive = false
        playing = true
    }

    fun endBall() {
        playing = false
        ballSaveTimeLeft = 0f
        skillShotTimeLeft = 0f
        overdriveTimeLeft = 0f
    }

    // ------------------------------------------------------------ ticking

    fun update(dt: Float) {
        if (!playing) return

        if (ballSaveTimeLeft > 0f) {
            ballSaveTimeLeft -= dt
            if (ballSaveTimeLeft <= 0f) {
                ballSaveTimeLeft = 0f
                if (launchedThisBall) listener.onRuleEvent(RuleEvent.BallSaveExpired)
            }
        }

        if (skillShotTimeLeft > 0f) {
            skillShotTimeLeft -= dt
            if (skillShotTimeLeft <= 0f) {
                skillShotTimeLeft = 0f
                if (skillShotLane >= 0) {
                    skillShotLane = -1
                    listener.onRuleEvent(RuleEvent.SkillShotMissed)
                }
            }
        }

        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) comboCount = 0
        }

        if (overdriveTimeLeft > 0f) {
            overdriveTimeLeft -= dt
            if (overdriveTimeLeft <= 0f) {
                overdriveTimeLeft = 0f
                listener.onRuleEvent(RuleEvent.OverdriveEnd)
            }
        }
    }

    // ------------------------------------------------------------ scoring

    private fun addScore(points: Long) {
        score += points * scoreMultiplier * (if (overdriveTimeLeft > 0f) 2 else 1)
    }

    // ------------------------------------------------------------ events

    fun onSimEvent(event: SimEvent) {
        if (!playing && event !is SimEvent.BallDrained) {
            // Still track pure FX events but not scoring outside play.
            return
        }
        when (event) {
            is SimEvent.BallLaunched -> onLaunched(event)
            is SimEvent.Rollover -> onRollover(event)
            is SimEvent.StandupHit -> onStandup(event)
            is SimEvent.DropTargetDown -> onDropTarget(event)
            is SimEvent.DropBankComplete -> onBankComplete()
            is SimEvent.BumperHit -> onBumper(event)
            is SimEvent.SlingHit -> addScore(75)
            is SimEvent.SpinnerSpins -> onSpinner(event)
            is SimEvent.ShotLane -> onShotLane(event)
            is SimEvent.HoleCapture -> onScoopCapture(event)
            is SimEvent.BallDrained -> onDrained(event)
            is SimEvent.FlipperHit -> addScore(10)
            else -> {}
        }
    }

    private fun onLaunched(e: SimEvent.BallLaunched) {
        launchedThisBall = true
        if (skillShotLane < 0 && !multiballActive) {
            // Light a random lane for the skill shot.
            skillShotLane = (Math.random() * 3).toInt()
            skillShotTimeLeft = SKILL_SHOT_TIME
            listener.onRuleEvent(RuleEvent.SkillShotLit)
        }
        if (!multiballActive) {
            ballSaveTimeLeft = BALL_SAVE_TIME
            listener.onRuleEvent(RuleEvent.BallSaveStart(BALL_SAVE_TIME))
        }
    }

    private fun onRollover(e: SimEvent.Rollover) {
        when (e.lane) {
            Ids.LANE_N, Ids.LANE_E, Ids.LANE_X -> {
                val idx = when (e.lane) {
                    Ids.LANE_N -> 0
                    Ids.LANE_E -> 1
                    else -> 2
                }
                // Skill shot?
                if (skillShotLane >= 0 && idx == skillShotLane && skillShotTimeLeft > 0f) {
                    skillShotLane = -1
                    skillShotTimeLeft = 0f
                    addScore(15_000)
                    listener.onRuleEvent(RuleEvent.SkillShotMade(idx, 15_000))
                    lightLock()
                }
                addScore(1_000)
                if (!lanesDone[idx]) {
                    lanesDone[idx] = true
                    if (lanesDone.all { it }) {
                        statLanesComplete++
                        lanesDone.fill(false)
                        if (bonusMultiplier < MAX_BONUS_MULTIPLIER) {
                            bonusMultiplier++
                            listener.onRuleEvent(RuleEvent.BonusMultiplierUp(bonusMultiplier))
                        }
                        addScore(5_000)
                        awardLockCredit()
                        listener.onRuleEvent(RuleEvent.LaneComplete(3, bonusMultiplier))
                    }
                }
            }
            Ids.INLANE_L, Ids.INLANE_R -> addScore(500)
            Ids.OUTLANE_L, Ids.OUTLANE_R -> addScore(1_000)
        }
    }

    private fun onStandup(e: SimEvent.StandupHit) {
        addScore(1_500)
        statStandupHits++
        val idx = if (e.target == Ids.STANDUP_L) 0 else 1
        if (!standupsDone[idx]) {
            standupsDone[idx] = true
            if (standupsDone.all { it }) {
                standupsDone.fill(false)
                addScore(7_500)
                awardLockCredit()
                bumpObjective(OBJ_STANDUPS)
            }
        }
    }

    private fun onDropTarget(e: SimEvent.DropTargetDown) {
        addScore(2_000)
        statDropTargets++
    }

    private fun onBankComplete() {
        banksCompleted++
        addScore(10_000)
        awardLockCredit()
        bumpObjective(OBJ_DROPS)
        if (banksCompleted >= 2 && !extraBallLit && extraBallsWon == 0) {
            extraBallLit = true
            listener.onRuleEvent(RuleEvent.ExtraBallLit)
        }
    }

    private fun onBumper(e: SimEvent.BumperHit) {
        addScore(350)
        statBumperHits++
        if (statBumperHits >= 25) bumpObjective(OBJ_BUMPERS)
    }

    private fun onSpinner(e: SimEvent.SpinnerSpins) {
        addScore(150L * e.revs)
        statSpinnerRevs += e.revs
        if (statSpinnerRevs >= 25) bumpObjective(OBJ_SPINNER)
    }

    private fun onShotLane(e: SimEvent.ShotLane) {
        when (e.id) {
            Ids.SHOT_ORBIT -> {
                statOrbits++
                addScore(3_000)
                bumpObjective(OBJ_ORBIT)
                registerCombo()
                if (superJackpotLit) {
                    superJackpotLit = false
                    superJackpots++
                    val points = 100_000L
                    addScore(points)
                    listener.onRuleEvent(RuleEvent.SuperJackpot(points))
                }
            }
            Ids.SHOT_RAMP -> {
                // Scoring happens at scoop capture; combo counts here.
                registerCombo()
            }
        }
    }

    private fun registerCombo() {
        if (comboTimer > 0f) {
            comboCount++
        } else {
            comboCount = 1
        }
        comboTimer = COMBO_WINDOW
        if (comboCount > 1) {
            val points = 1_000L * comboCount
            addScore(points)
            if (comboCount > bestCombo) bestCombo = comboCount
            listener.onRuleEvent(RuleEvent.Combo(comboCount, points))
        }
    }

    // ------------------------------------------------------------ scoop

    private fun onScoopCapture(e: SimEvent.HoleCapture) {
        if (holdingBall) return
        // Extra ball?
        if (extraBallLit) {
            extraBallLit = false
            extraBallsWon++
            addScore(25_000)
            listener.onRuleEvent(RuleEvent.ExtraBallAwarded())
            actions.captureBall(e.ball)
            holdingBall = true
            actions.ejectFromScoop(e.ball, 1.2f)
            return
        }

        if (multiballActive) {
            // Jackpot!
            actions.captureBall(e.ball)
            holdingBall = true
            jackpotsCollected++
            val base = 50_000L + 25_000L * min(jackpotsCollected, 5)
            addScore(base)
            listener.onRuleEvent(RuleEvent.Jackpot(jackpotsCollected, base))
            bumpObjective(OBJ_RAMP)
            statRampShots++
            if (jackpotsCollected >= JACKPOTS_FOR_SUPER && !superJackpotLit) {
                superJackpotLit = true
                listener.onRuleEvent(RuleEvent.SuperJackpotLit)
            }
            actions.ejectFromScoop(e.ball, 1.0f)
            return
        }

        if (lockLit && ballsLocked < 2) {
            // Lock the ball. It parks in the lock (not the scoop), so the
            // scoop is immediately available for another capture.
            lockLit = false
            lockCredits = 0
            actions.captureBall(e.ball)
            holdingBall = false
            ballsLocked++
            statRampShots++
            bumpObjective(OBJ_RAMP)
            listener.onRuleEvent(RuleEvent.BallLocked(ballsLocked))
            if (ballsLocked >= 2) {
                // Multiball! Eject both after a beat.
                multiballActive = true
                listener.onRuleEvent(RuleEvent.MultiballReady)
            }
            // Note: serveBall/eject sequenced by the host via rule events.
            return
        }

        // Plain scoop score: eject.
        addScore(4_000)
        actions.captureBall(e.ball)
        holdingBall = true
        actions.ejectFromScoop(e.ball, 0.6f)
    }

    /** Called by the host when a held ball has been ejected back into play. */
    fun onScoopEjected() {
        holdingBall = false
    }

    /**
     * Multiball sequencing: after the locking capture, the host asks what to
     * do next. Returns the number of balls to eject from the scoop now.
     */
    fun beginMultiballEject(): Int {
        if (!multiballActive || ballsLocked == 0) return 0
        val n = ballsLocked
        ballsLocked = 0
        listener.onRuleEvent(RuleEvent.MultiballStart(n + 1))
        return n
    }

    // ------------------------------------------------------------ drain

    private fun onDrained(e: SimEvent.BallDrained) {
        if (multiballActive) {
            // Check remaining live balls: MB ends when down to <= 1.
            val remaining = actions.liveBalls()
            if (remaining <= 1) {
                multiballActive = false
                jackpotsCollected = 0
                listener.onRuleEvent(RuleEvent.MultiballEnd)
                // Fall through to normal drain handling for the last ball.
            } else {
                return
            }
        }

        if (!playing) return

        if (ballSaveTimeLeft > 0f) {
            ballSaveTimeLeft = 0f
            listener.onRuleEvent(RuleEvent.BallSaveUsed)
            actions.serveBall()
            return
        }

        // Ball really ended: compute bonus.
        val base = (
            statBumperHits * 250L +
                statLanesComplete * 3_000L +
                statOrbits * 1_500L +
                statRampShots * 2_500L +
                statSpinnerRevs * 100L +
                statDropTargets * 500L +
                statStandupHits * 400L +
                bestCombo * 500L
            )
        val total = base * bonusMultiplier
        score += total
        listener.onRuleEvent(RuleEvent.BonusTally(base, bonusMultiplier, total))
        endBall()
    }

    // ------------------------------------------------------------ helpers

    private fun awardLockCredit() {
        if (lockLit || multiballActive || ballsLocked >= 2) return
        lockCredits++
        if (lockCredits >= 2) {
            lightLock()
        }
    }

    private fun lightLock() {
        if (lockLit || multiballActive || ballsLocked >= 2) return
        lockLit = true
        lockCredits = 2
        listener.onRuleEvent(RuleEvent.LockLit)
    }

    private fun bumpObjective(id: Int) {
        if (objectivesDone[id]) return
        objectivesDone[id] = true
        listener.onRuleEvent(RuleEvent.ObjectiveComplete(id))
        if (objectivesDone.all { it }) {
            overdriveTimeLeft = OVERDRIVE_TIME
            scoreMultiplier = maxOf(scoreMultiplier, 1)
            listener.onRuleEvent(RuleEvent.OverdriveStart(OVERDRIVE_TIME))
        }
    }

    /** Bonus preview for the HUD/ball-end screen. */
    fun bonusEstimate(): Long {
        val base = (
            statBumperHits * 250L +
                statLanesComplete * 3_000L +
                statOrbits * 1_500L +
                statRampShots * 2_500L +
                statSpinnerRevs * 100L +
                statDropTargets * 500L +
                statStandupHits * 400L
            )
        return base * bonusMultiplier
    }
}
