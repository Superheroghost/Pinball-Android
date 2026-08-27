package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.sim.Ids
import com.superheroghost.neonpinball.sim.RuleEvent
import com.superheroghost.neonpinball.sim.RuleEventListener
import com.superheroghost.neonpinball.sim.RulesEngine
import com.superheroghost.neonpinball.sim.SimActions
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Rules engine unit tests: scripted SimEvents drive the engine with fake
 * actions; asserts progression, scoring, ball save, locks, multiball,
 * jackpots, extra ball, overdrive, combos, bonus.
 */
class FakeActions : SimActions {
    var served = 0
    val captured = ArrayList<Int>()
    val ejects = ArrayList<Pair<Int, Float>>()
    var live = 1
    var locked = 0

    override fun serveBall() {
        served++
    }

    override fun captureBall(ballId: Int) {
        captured.add(ballId)
        locked++
        live--
    }

    override fun ejectFromScoop(ballId: Int, delaySec: Float) {
        ejects.add(ballId to delaySec)
        locked--
        live++
    }

    override fun liveBalls(): Int = live

    override fun lockedBalls(): Int = locked
}

class RulesTest {
    var failures = 0
    var checks = 0

    fun check(cond: Boolean, msg: String) {
        checks++
        if (!cond) {
            failures++
            println("  FAIL: $msg")
        }
    }

    fun testBasicScoring() {
        println("== basic scoring")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()

        r.onSimEvent(SimEvent.BumperHit(0, 0.2f, 0.6f, 1f))
        r.onSimEvent(SimEvent.SlingHit(0, 0.1f, 0.2f, 1f))
        r.onSimEvent(SimEvent.FlipperHit(true, 1f, 0f, 0f))
        check(r.score == 350L + 75L + 10L, "score ${r.score} == 435")
    }

    fun testLanesAndBonusMultiplier() {
        println("== lanes / bonus multiplier")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()

        r.onSimEvent(SimEvent.BallLaunched(0, 3f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        check(r.bonusMultiplier == 2, "bonus mult 2, got ${r.bonusMultiplier}")
        check(r.lanesDone.all { !it }, "lanes reset after completion")
        val laneComplete = events.any { it is RuleEvent.BonusMultiplierUp }
        check(laneComplete, "BonusMultiplierUp emitted")

        // Second full completion -> 3x.
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        check(r.bonusMultiplier == 3, "bonus mult 3, got ${r.bonusMultiplier}")

        // Lanes also give 2 lock credits -> LockLit.
        check(r.lockLit, "lock lit after 2 credits")
    }

    fun testSkillShot() {
        println("== skill shot")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()

        r.onSimEvent(SimEvent.BallLaunched(0, 3f))
        check(r.skillShotLane in 0..2, "skill lane lit")
        val lane = r.skillShotLane
        // Hit the lit lane in time.
        val laneId = when (lane) {
            0 -> Ids.LANE_N
            1 -> Ids.LANE_E
            else -> Ids.LANE_X
        }
        val before = r.score
        r.onSimEvent(SimEvent.Rollover(laneId, 0f, 0f))
        check(events.any { it is RuleEvent.SkillShotMade }, "SkillShotMade emitted")
        check(r.score - before >= 15_000, "skill points awarded (got ${r.score - before})")
        check(r.lockLit, "skill shot awards lock credit -> lit")

        // Second launch: timeout -> missed.
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))
        val lane2 = r.skillShotLane
        check(lane2 in 0..2, "second skill lit")
        for (i in 0 until 140) r.update(1f / 30f)
        check(r.skillShotTimeLeft == 0f, "skill timed out")
        check(events.any { it is RuleEvent.SkillShotMissed }, "SkillShotMissed emitted")
    }

    fun testBallSave() {
        println("== ball save")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))

        // Drain within window -> re-serve, ball continues.
        a.live = 0
        r.onSimEvent(SimEvent.BallDrained(0))
        check(a.served == 1, "ball save served once, got ${a.served}")
        check(r.playing, "still playing after save")
        a.live = 1

        // Drain after window -> ball ends with bonus.
        a.live = 1
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))
        for (i in 0 until 20) r.onSimEvent(SimEvent.BumperHit(0, 0.2f, 0.6f, 1f))
        for (i in 0 until 300) r.update(1f / 30f)
        a.live = 0
        val scoreBefore = r.score
        r.onSimEvent(SimEvent.BallDrained(0))
        check(!r.playing, "ball ended after save expiry")
        check(r.score > scoreBefore, "bonus added at ball end")
        check(events.any { it is RuleEvent.BonusTally }, "BonusTally emitted")
    }

    fun testLocksAndMultiball() {
        println("== locks / multiball / jackpot")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()

        // Light lock via lanes + standups.
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_L, 0f, 0f))
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_R, 0f, 0f))
        check(r.lockLit, "lock lit")

        // Lock 1: scoop capture.
        a.live = 1
        r.onSimEvent(SimEvent.HoleCapture(0, 0))
        check(r.ballsLocked == 1, "one locked, got ${r.ballsLocked}")
        check(a.captured.contains(0), "ball 0 captured")
        check(a.ejects.isEmpty(), "locked ball not ejected")

        // Relight (credits reset by lock) and lock 2 -> MB ready.
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_N, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_E, 0f, 0f))
        r.onSimEvent(SimEvent.Rollover(Ids.LANE_X, 0f, 0f))
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_L, 0f, 0f))
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_R, 0f, 0f))
        check(r.lockLit, "lock relit")
        // serve replacement ball (live=1 simulates it)
        a.live = 1
        r.onSimEvent(SimEvent.HoleCapture(1, 0))
        check(r.ballsLocked == 2, "two locked")
        check(r.multiballActive, "multiball active")
        check(events.any { it is RuleEvent.MultiballReady }, "MultiballReady emitted")

        // Host ejects parked balls.
        val n = r.beginMultiballEject()
        check(n == 2, "beginMultiballEject returns 2, got $n")
        check(r.ballsLocked == 0, "locks consumed")

        // Jackpot: ramp shot + scoop capture during MB. (The host clears the
        // holding flag when the eject lands; simulate that between captures.)
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_RAMP, 1, 0f, 0f, 1f))
        r.onSimEvent(SimEvent.HoleCapture(1, 0))
        check(r.jackpotsCollected == 1, "1 jackpot, got ${r.jackpotsCollected}")
        check(a.ejects.isNotEmpty(), "jackpot ball ejected")
        r.onScoopEjected()

        // Two more jackpots light super.
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_RAMP, 1, 0f, 0f, 1f))
        r.onSimEvent(SimEvent.HoleCapture(1, 0))
        r.onScoopEjected()
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_RAMP, 1, 0f, 0f, 1f))
        r.onSimEvent(SimEvent.HoleCapture(1, 0))
        r.onScoopEjected()
        check(r.jackpotsCollected == 3, "3 jackpots")
        check(r.superJackpotLit, "super lit")

        // Orbit -> super jackpot.
        val before = r.score
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_ORBIT, 1, 0f, 0f, 1f))
        check(!r.superJackpotLit, "super collected")
        check(r.score - before >= 100_000L, "super points (got ${r.score - before})")
        check(r.superJackpots == 1, "super count")
    }

    fun testExtraBall() {
        println("== extra ball")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))

        // Two bank completions light extra ball.
        r.onSimEvent(SimEvent.DropTargetDown(0, 0, 0f, 0f))
        r.onSimEvent(SimEvent.DropTargetDown(0, 1, 0f, 0f))
        r.onSimEvent(SimEvent.DropTargetDown(0, 2, 0f, 0f))
        r.onSimEvent(SimEvent.DropBankComplete(0))
        r.onSimEvent(SimEvent.DropTargetDown(0, 0, 0f, 0f))
        r.onSimEvent(SimEvent.DropTargetDown(0, 1, 0f, 0f))
        r.onSimEvent(SimEvent.DropTargetDown(0, 2, 0f, 0f))
        r.onSimEvent(SimEvent.DropBankComplete(0))
        check(r.extraBallLit, "extra ball lit")

        // Bank completions also award lock credits (2) -> lock lit; capture is lock first!
        // Rules order: lock takes priority. Verify extra ball collect when lock not lit:
        // (in this script lockLit is true, so capture locks instead)
        check(r.lockLit, "lock also lit from bank credits")
    }

    fun testOverdriveAndCombos() {
        println("== overdrive / combos")
        val a = FakeActions()
        val events = ArrayList<RuleEvent>()
        val r = RulesEngine(a, RuleEventListener { events.add(it) })
        r.newGame()
        r.resetForBall()
        r.onSimEvent(SimEvent.BallLaunched(0, 3f))

        // Complete all 6 objectives.
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_ORBIT, 0, 0f, 0f, 1f)) // orbit
        r.onSimEvent(SimEvent.RampEntered(0)) // (no scoring; ramp via scoop)
        // drops:
        r.onSimEvent(SimEvent.DropBankComplete(0)) // -> OBJ_DROPS
        // standups:
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_L, 0f, 0f))
        r.onSimEvent(SimEvent.StandupHit(Ids.STANDUP_R, 0f, 0f))
        // spinner 25 revs:
        r.onSimEvent(SimEvent.SpinnerSpins(0, 25))
        // bumpers 25 hits:
        for (i in 0 until 25) r.onSimEvent(SimEvent.BumperHit(0, 0.2f, 0.6f, 1f))
        // ramp objective via lock path: light lock then capture (scoop) —
        // simpler: capture while plain (no lock lit) does not bump ramp...
        // Use lock path: light lock, capture.
        check(!r.objectivesDone.all { it }, "not all done yet")
        // Lock lit already via bank+standups credits.
        r.onSimEvent(SimEvent.HoleCapture(0, 0)) // locks ball 1 -> OBJ_RAMP
        check(r.objectivesDone.all { it }, "all objectives done, got ${r.objectivesDone.toList()}")
        check(r.overdriveTimeLeft > 0f, "overdrive running")
        check(events.any { it is RuleEvent.OverdriveStart }, "OverdriveStart emitted")

        // During overdrive scoring doubles.
        val before = r.score
        r.onSimEvent(SimEvent.BumperHit(0, 0.2f, 0.6f, 1f))
        check(r.score - before == 700L, "overdrive doubles bumper 350->700, got ${r.score - before}")

        // Timeout ends it.
        for (i in 0 until 700) r.update(1f / 30f)
        check(r.overdriveTimeLeft == 0f, "overdrive ended")
        val before2 = r.score
        r.onSimEvent(SimEvent.BumperHit(0, 0.2f, 0.6f, 1f))
        check(r.score - before2 == 350L, "normal scoring after overdrive, got ${r.score - before2}")

        // Combos: two orbits within window.
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_ORBIT, 0, 0f, 0f, 1f))
        r.onSimEvent(SimEvent.ShotLane(Ids.SHOT_ORBIT, 0, 0f, 0f, 1f))
        check(r.comboCount == 2, "combo 2, got ${r.comboCount}")
    }

    fun run() {
        testBasicScoring()
        testLanesAndBonusMultiplier()
        testSkillShot()
        testBallSave()
        testLocksAndMultiball()
        testExtraBall()
        testOverdriveAndCombos()
        println()
        println("rules tests: $checks checks, $failures failures")
        if (failures > 0) kotlin.system.exitProcess(1)
        println("ALL PASS")
    }
}

object RulesTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        RulesTest().run()
    }
}
