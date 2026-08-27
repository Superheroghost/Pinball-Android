package com.superheroghost.neonpinball.sim

/** What the rules engine asks the simulation to do. */
interface SimActions {
    /** Serve a fresh ball to the plunger (same ball number). */
    fun serveBall()

    /** Capture (remove from play) a live ball, e.g. scoop lock. */
    fun captureBall(ballId: Int)

    /** Pop a ball out of the scoop down the ramp after a delay. */
    fun ejectFromScoop(ballId: Int, delaySec: Float)

    /** Live (in-play) ball count. */
    fun liveBalls(): Int

    /** Total balls currently held in the scoop lock. */
    fun lockedBalls(): Int
}

/** Rule-level events for UI/audio/FX. */
sealed class RuleEvent {
    object SkillShotLit : RuleEvent()
    class SkillShotMade(val lane: Int, val points: Long) : RuleEvent()
    object SkillShotMissed : RuleEvent()

    class LaneComplete(val lanes: Int, val bonusMultiplier: Int) : RuleEvent()
    class BonusMultiplierUp(val newMultiplier: Int) : RuleEvent()

    object LockLit : RuleEvent()
    class BallLocked(val lockedCount: Int) : RuleEvent()
    object MultiballReady : RuleEvent()
    class MultiballStart(val balls: Int) : RuleEvent()
    object MultiballEnd : RuleEvent()

    class Jackpot(val count: Int, val points: Long) : RuleEvent()
    object SuperJackpotLit : RuleEvent()
    class SuperJackpot(val points: Long) : RuleEvent()

    object ExtraBallLit : RuleEvent()
    class ExtraBallAwarded : RuleEvent()

    class OverdriveStart(val duration: Float) : RuleEvent()
    object OverdriveEnd : RuleEvent()
    class ObjectiveComplete(val objective: Int) : RuleEvent()

    class BallSaveStart(val duration: Float) : RuleEvent()
    object BallSaveUsed : RuleEvent()
    object BallSaveExpired : RuleEvent()

    class Combo(val count: Int, val points: Long) : RuleEvent()
    class ScoreMultChange(val multiplier: Int) : RuleEvent()

    class BonusTally(val base: Long, val multiplier: Int, val total: Long) : RuleEvent()
}

/** Listener for rule events; implemented by the game/presentation layer. */
fun interface RuleEventListener {
    fun onRuleEvent(event: RuleEvent)
}
