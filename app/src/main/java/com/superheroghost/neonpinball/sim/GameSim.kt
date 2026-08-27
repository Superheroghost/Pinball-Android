package com.superheroghost.neonpinball.sim

import org.jbox2d.common.Vec2

/**
 * Owns the physics world + table and drives the fixed-timestep simulation.
 * Pure Kotlin: runs on Android and in the desktop test harness.
 *
 * Responsibilities:
 *  - ball lifecycle (plunger, live, ramp, captured, drain),
 *  - ramp plane switching,
 *  - plunger pull/release,
 *  - delivering sim events to the rules engine,
 *  - stuck/escaped-ball watchdogs,
 *  - debug/testing hooks.
 */
class GameSim(debugStage: Int = 0) : SimActions {
    val physics = PhysicsWorld()
    val table = TableGeometry(physics, debugStage)

    /** Event handler; set by the rules engine. Called on the sim thread. */
    var onEvent: ((SimEvent) -> Unit)? = null

    /** Balls by id (ids are stable per game). */
    val balls = ArrayList<Ball>(6)

    /** Physics step counter for deterministic behaviour. */
    var stepCount = 0L
        private set

    /** Sim time in seconds. */
    var time = 0f
        private set

    // ------------------------------------------------------------ plunger
    var plungerPull = 0f // 0..1
        private set
    private var plungerHeld = false

    /** Set while a launched ball may still be a skill shot. */
    var lastLaunchSpeed = 0f
        private set

    private val pendingEjects = ArrayList<EjectRequest>(4)
    private val tmp = Vec2()
    private val tmp2 = Vec2()

    private class EjectRequest(val ballId: Int, var atTime: Float)

    private var accumulator = 0f

    /** Debug switches. */
    var debugSlowMotion = false
    var stuckNudges = 0
        private set
    var escapedBalls = 0
        private set

    // Per-ball stuck tracking (indexed by ball id, ids are small).
    private val stuckTime = FloatArray(8)
    private val lowSpeedTime = FloatArray(8)
    private val stuckCheckX = FloatArray(8)
    private val stuckCheckY = FloatArray(8)

    // ------------------------------------------------------------ balls

    fun createBall(id: Int): Ball {
        val b = Ball(id, physics)
        balls.add(b)
        return b
    }

    /** True if a ball is already waiting on the plunger. */
    fun plungerOccupied(): Boolean = balls.any { it.state == BallState.ON_PLUNGER && it.body.isActive }

    /**
     * Serve a ball that is neither parked in the lock nor already in play.
     * Prevents teleporting a locked ball back onto the plunger, and refuses
     * to stack a second ball on an occupied plunger (returns -1).
     */
    fun serveFreeBall(): Int {
        if (plungerOccupied()) return -1
        var id = 0
        while (id < 8) {
            val b = balls.find { it.id == id }
            val busy = parked.contains(id) || (b != null && b.body.isActive)
            if (!busy) break
            id++
        }
        if (id >= 8) id = balls.size.coerceAtMost(7)
        serveBall(id)
        return id
    }

    /** Place a ball (existing or new) on the plunger. */
    fun serveBall(id: Int) {
        val ball = balls.find { it.id == id } ?: createBall(id)
        table.plungerBallPos(tmp)
        ball.place(tmp.x, tmp.y)
        ball.state = BallState.ON_PLUNGER
        ball.setRampMode(false)
        ball.restoreToWorld()
    }

    /** Debug: spawn an extra live ball at a position. */
    fun debugSpawnBall(x: Float, y: Float, vx: Float = 0f, vy: Float = 0f): Int {
        val id = balls.size
        val ball = createBall(id)
        ball.place(x, y, vx, vy)
        ball.state = BallState.LIVE
        return id
    }

    fun ballById(id: Int): Ball? = balls.find { it.id == id }

    // ------------------------------------------------------------ plunger

    fun setPlungerHeld(held: Boolean) {
        plungerHeld = held
        if (!held && plungerPull > 0.02f) {
            releasePlunger()
        }
    }

    /** Set pull 0..1 from touch drag. */
    fun setPlungerPull(pull: Float) {
        plungerPull = pull.coerceIn(0f, 1f)
        plungerHeld = true
    }

    private fun releasePlunger() {
        val t = TableTuning
        val speed = t.PLUNGER_MIN_SPEED + (t.PLUNGER_MAX_SPEED - t.PLUNGER_MIN_SPEED) * plungerPull
        // Any ball resting on the plunger gets kicked.
        for (b in balls) {
            if (b.state == BallState.ON_PLUNGER && b.body.isActive) {
                val p = b.body.position
                if (p.y < t.PLUNGER_Y + 0.12f) {
                    b.setVelocity(b.body.linearVelocity.x * 0.3f, speed)
                    lastLaunchSpeed = speed
                }
            }
        }
        plungerPull = 0f
    }

    // ------------------------------------------------------------ stepping

    fun update(dt: Float) {
        val scale = if (debugSlowMotion) 0.25f else 1f
        accumulator += dt * scale
        var steps = 0
        while (accumulator >= TableTuning.FIXED_DT && steps < TableTuning.MAX_SUBSTEPS) {
            accumulator -= TableTuning.FIXED_DT
            fixedUpdate(TableTuning.FIXED_DT)
            steps++
        }
        if (steps == TableTuning.MAX_SUBSTEPS) accumulator = 0f
    }

    private fun fixedUpdate(dt: Float) {
        time += dt
        stepCount++

        for (b in balls) b.preStep()

        // Elements.
        table.flipperL.update(dt)
        table.flipperR.update(dt)
        for (i in table.bumpers.indices) table.bumpers[i].update(dt)
        for (i in table.slings.indices) table.slings[i].update(dt)
        table.dropBank.update(dt)
        table.spinner.update(dt)
        table.shooterGate.update(dt)
        table.scoop.update(dt)
        for (e in table.standups.values) e.update(dt)

        // Keep the on-plunger ball resting on the plunger face.
        val t = TableTuning
        for (b in balls) {
            if (b.state == BallState.ON_PLUNGER && b.body.isActive) {
                val p = b.body.position
                if (p.y < t.PLUNGER_Y && b.body.linearVelocity.y < 0.05f) {
                    // Plunger face support (the lane floor is slightly lower).
                    b.body.setTransform(Vec2(p.x, t.PLUNGER_Y), 0f)
                    b.body.setLinearVelocity(Vec2(b.body.linearVelocity.x * 0.5f, 0f))
                }
            }
        }

        physics.stepOnce()

        for (b in balls) b.postStep()

        postPhysicsChecks(dt)

        // Deliver and clear events once per fixed step.
        if (onEvent != null) {
            physics.events.forEachEvent { onEvent!!.invoke(it) }
        }
        physics.events.clear()

        processEjects()
    }

    /** Drain detection, ramp switching from shot sensors, watchdogs. */
    private fun postPhysicsChecks(dt: Float) {
        val t = TableTuning
        for (b in balls) {
            if (!b.body.isActive) continue
            val p = b.body.position
            val v = b.body.linearVelocity

            // Drain.
            if (p.y < t.DRAIN_Y && b.state != BallState.ON_PLUNGER) {
                b.state = BallState.DRAINED
                b.removeFromWorld()
                physics.events.add(SimEvent.BallDrained(b.id))
                continue
            }

            // Escaped the table entirely (should never happen; watchdog).
            if (p.x < -0.05f || p.x > t.TABLE_W + 0.05f || p.y > t.TABLE_H + 0.15f || p.y < -0.05f) {
                escapedBalls++
                if (b.state == BallState.ON_PLUNGER) {
                    serveBall(b.id)
                } else {
                    b.state = BallState.DRAINED
                    b.removeFromWorld()
                    physics.events.add(SimEvent.BallDrained(b.id))
                }
                continue
            }

            // Ramp-exit fallback: the mouth-exit sensor normally flips the
            // ball back to the playfield plane. If it was missed (fast ball),
            // any on-ramp ball below the mouth has already left the tube, so
            // switch planes here before it can drift through playfield walls.
            if (b.state == BallState.ON_RAMP && b.onRamp && (p.y < 0.50f || p.x < 0.095f || p.x > 0.34f)) {
                b.setRampMode(false)
                b.state = BallState.LIVE
            }

            // Shooter lane logic: passing the gate means the ball launched.
            if (b.state == BallState.ON_PLUNGER) {
                if (p.x < t.SHOOTER_X_INNER - TableTuning.BALL_R) {
                    b.state = BallState.LIVE
                    physics.events.add(SimEvent.BallLaunched(b.id, lastLaunchSpeed))
                }
            }

            // Stuck watchdog ("ball search"): if a live ball barely moves for
            // 3.5s anywhere on the playfield, nudge it; escalate if it stays
            // stuck; relocate as a last resort. Position-based, so a ball
            // cradled on a buzzing flipper motor still counts as stuck.
            if (b.state == BallState.LIVE && !b.onRamp) {
                if (lowSpeedTime[b.id] == 0f) {
                    // New slow period: anchor the stuck origin here.
                    stuckCheckX[b.id] = p.x
                    stuckCheckY[b.id] = p.y
                }
                lowSpeedTime[b.id] += dt
                if (lowSpeedTime[b.id] > 3.5f) {
                    lowSpeedTime[b.id] = 0.001f // keep origin anchored
                    val dx = p.x - stuckCheckX[b.id]
                    val dy = p.y - stuckCheckY[b.id]
                    val moved2 = dx * dx + dy * dy
                    if (moved2 < 0.008f * 0.008f) {
                        // Still within 8mm of the anchor: genuinely stuck.
                        stuckTime[b.id]++
                        val towardsCentre = if (p.x < t.TABLE_W * 0.5f) 1f else -1f
                        when {
                            stuckTime[b.id] >= 3f -> {
                                // Ball search: relocate to a clear spot above.
                                val rx = p.x.coerceIn(0.12f, t.SHOOTER_X_INNER - 0.12f)
                                b.place(rx, (p.y + 0.10f).coerceAtMost(0.5f), towardsCentre * 0.2f, -0.3f)
                                stuckTime[b.id] = 0f
                                lowSpeedTime[b.id] = 0f
                            }
                            stuckTime[b.id] == 2f -> b.addVelocity(towardsCentre * 0.7f, -0.45f)
                            else -> b.addVelocity(towardsCentre * 0.4f, -0.25f)
                        }
                        stuckNudges++
                    } else if (moved2 > 0.025f * 0.025f) {
                        // Roaming freely again.
                        stuckTime[b.id] = 0f
                        lowSpeedTime[b.id] = 0f
                    }
                    // In between (8..25mm): keep waiting with origin anchored.
                }
            } else {
                lowSpeedTime[b.id] = 0f
                stuckTime[b.id] = 0f
            }
        }
    }

    // ------------------------------------------------------------ events

    /** Handle ShotLane events that drive layer switches. Called by rules. */
    fun handleShotSensor(id: Int, ballId: Int, vy: Float) {
        when (id) {
            Ids.SHOT_RAMP -> {
                val b = ballById(ballId) ?: return
                if (!b.onRamp && vy > 0.25f && b.state == BallState.LIVE) {
                    b.setRampMode(true)
                    b.state = BallState.ON_RAMP
                    // Redirect along the tube's first segment.
                    val speed = (b.speed * 0.92f + 0.30f).coerceIn(1.35f, 3.1f)
                    b.setVelocity(-0.535f * speed, 0.845f * speed)
                }
            }
            Ids.SHOT_MOUTH_EXIT -> {
                val b = ballById(ballId) ?: return
                if (b.onRamp && vy < -0.15f) {
                    b.setRampMode(false)
                    if (b.state == BallState.ON_RAMP) b.state = BallState.LIVE
                }
            }
        }
    }

    /** Remove a ball from play (scoop lock). */
    override fun captureBall(ballId: Int) {
        val b = ballById(ballId) ?: return
        b.state = BallState.CAPTURED
        b.setRampMode(false)
        b.removeFromWorld()
        if (!parked.contains(ballId)) parked.add(ballId)
    }

    /** Balls held in the scoop (locks). */
    val parked = ArrayList<Int>()

    /** Schedule a ball to pop out of the scoop top and roll down the tube. */
    fun scheduleScoopEject(ballId: Int, delay: Float) {
        pendingEjects.add(EjectRequest(ballId, time + delay))
    }

    /** Eject: place the ball at the top of the tube heading down it. */
    fun ejectFromScoop(id: Int) {
        val b = balls.find { it.id == id } ?: createBall(id)
        val p0 = tubeTop
        b.place(p0.x, p0.y, -0.20f, -1.55f)
        b.setRampMode(true)
        b.state = BallState.ON_RAMP
        b.restoreToWorld()
        parked.remove(id)
        physics.events.add(SimEvent.HoleEject(b.id))
    }

    private val tubeTop = Vec2(0.1345f, 0.8955f)

    private fun processEjects() {
        if (pendingEjects.isEmpty()) return
        var i = 0
        while (i < pendingEjects.size) {
            if (time >= pendingEjects[i].atTime) {
                ejectFromScoop(pendingEjects[i].ballId)
                pendingEjects.removeAt(i)
            } else {
                i++
            }
        }
    }

    /** Full reset for a new game: all balls to plunger state, targets up. */
    fun resetAll() {
        pendingEjects.clear()
        parked.clear()
        table.resetDynamicState()
        for (b in balls) {
            b.removeFromWorld()
        }
        balls.clear()
        physics.events.clear()
        accumulator = 0f
        serveBall(0)
    }

    /** Debug helpers used by the tuning harness. */
    fun debugResetPositions() {
        for (b in balls) {
            if (b.state == BallState.LIVE || b.state == BallState.ON_RAMP) {
                serveBall(b.id)
            }
        }
    }

    fun anyLiveBall(): Boolean = liveBallCount() > 0

    fun liveBallCount(): Int {
        var n = 0
        for (b in balls) {
            if (b.body.isActive && (b.state == BallState.LIVE || b.state == BallState.ON_RAMP)) n++
        }
        return n
    }

    // ------------------------------------------------------------ SimActions
    override fun serveBall() {
        serveFreeBall()
    }

    override fun ejectFromScoop(ballId: Int, delaySec: Float) {
        scheduleScoopEject(ballId, delaySec)
    }

    override fun liveBalls(): Int = liveBallCount()

    override fun lockedBalls(): Int = parked.size
}
