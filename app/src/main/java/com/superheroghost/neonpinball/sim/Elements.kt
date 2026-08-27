package com.superheroghost.neonpinball.sim

import org.jbox2d.dynamics.Body

/**
 * Pop bumper: solid post with a sensor ring. On contact the ball is pushed
 * away along the bumper->ball direction with a tuned impulse, plus a light
 * pulse, particles, haptic and sound emitted through the event bus.
 */
class Bumper(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
) : ContactTarget {
    var body: Body
    private var cooldown = 0f

    /** Visual ring intensity 0..1, decays after each hit. */
    var pulse = 0f
        private set

    /** Times hit this ball (drives "bumper frenzy" scoring and bonus). */
    var hits = 0

    init {
        body = physics.staticBody(x, y)
        physics.circleFixture(
            body, 0f, 0f, TableTuning.BUMPER_R,
            restitution = 0.25f, friction = 0.1f,
            category = Cat.BUMPER, mask = Cat.BALL, sensor = false,
        )
        physics.circleFixture(
            body, 0f, 0f, TableTuning.BUMPER_R + TableTuning.BALL_R * 0.55f,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL, sensor = true, target = this,
        )
    }

    fun reset() {
        pulse = 0f
    }

    fun update(dt: Float) {
        if (cooldown > 0f) cooldown -= dt
        if (pulse > 0f) pulse = (pulse - dt * 4.5f).coerceAtLeast(0f)
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        if (cooldown > 0f || ball.onRamp) return
        cooldown = TableTuning.BUMPER_COOLDOWN
        val dx = ball.body.position.x - x
        val dy = ball.body.position.y - y
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1e-5f) return
        val nx = dx / len
        val ny = dy / len
        val keep = ball.speed * TableTuning.BUMPER_KEEP
        ball.setVelocity(nx * (TableTuning.BUMPER_KICK + keep), ny * (TableTuning.BUMPER_KICK + keep))
        pulse = 1f
        hits++
        physics.events.add(SimEvent.BumperHit(id, x, y, 1f))
    }
}

/**
 * Slingshot: triangular kicker. The rubber face is the segment from
 * (ax,ay)-(bx,by); a ball hitting it hard enough is flung along [kickX,kickY].
 */
class Slingshot(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val left: Boolean,
    /** Triangle vertices, table space. */
    private val verts: FloatArray,
    /** Rubber face endpoints (the kicking edge). */
    ax: Float, ay: Float, bx: Float, by: Float,
    /** Kick direction (normalised). */
    @JvmField val kickX: Float,
    @JvmField val kickY: Float,
) : ContactTarget {
    private val body: Body
    private val sensorBody: Body
    private var cooldown = 0f
    var pulse = 0f
        private set

    /** Triangle outline for rendering. */
    val outline: FloatArray = verts.copyOf()

    init {
        body = physics.staticBody(0f, 0f)
        physics.polygonFixture(
            body, verts,
            restitution = 0.2f, friction = 0.08f,
            category = Cat.WALL, mask = Cat.BALL,
        )
        // Thin sensor over the kick face, offset slightly outward.
        val midX = (ax + bx) * 0.5f
        val midY = (ay + by) * 0.5f
        val fx = bx - ax
        val fy = by - ay
        val fl = Math.sqrt((fx * fx + fy * fy).toDouble()).toFloat()
        val ang = Math.atan2(fy.toDouble(), fx.toDouble()).toFloat()
        val offX = -kickY * 0.008f
        val offY = kickX * 0.008f
        sensorBody = physics.staticBody(midX + offX, midY + offY)
        physics.boxFixture(
            sensorBody, fl * 0.5f + 0.006f, 0.009f, 0f, 0f, ang,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL, sensor = true, target = this,
        )
    }

    fun update(dt: Float) {
        if (cooldown > 0f) cooldown -= dt
        if (pulse > 0f) pulse = (pulse - dt * 5f).coerceAtLeast(0f)
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        if (cooldown > 0f || ball.onRamp) return
        if (info.speed < TableTuning.SLING_MIN_SPEED) return
        cooldown = TableTuning.SLING_COOLDOWN
        val keep = ball.speed * TableTuning.SLING_KEEP
        ball.setVelocity(kickX * (TableTuning.SLING_KICK + keep), kickY * (TableTuning.SLING_KICK + keep))
        pulse = 1f
        physics.events.add(SimEvent.SlingHit(id, info.x, info.y, 1f))
    }
}

/** Rollover lane sensor (top lanes, inlanes, outlanes). */
class Rollover(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val w: Float = 0.032f,
    @JvmField val h: Float = 0.020f,
) : ContactTarget {
    private val body: Body
    private var insideCount = 0

    init {
        body = physics.staticBody(x, y)
        physics.boxFixture(
            body, w * 0.5f, h * 0.5f, 0f, 0f, 0f,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL or Cat.BALL_RAMP, sensor = true, target = this,
        )
    }

    override fun onBegin(info: ContactInfo) {
        insideCount++
        physics.events.add(SimEvent.Rollover(id, x, y))
    }

    override fun onEnd(info: ContactInfo) {
        if (insideCount > 0) insideCount--
    }
}

/** Stand-up target: scores on impact, never drops. */
class Standup(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
    /** Facing normal (towards the playfield). */
    @JvmField val nx: Float,
    @JvmField val ny: Float,
) : ContactTarget {
    private val body: Body
    private var cooldown = 0f
    var pulse = 0f
        private set

    init {
        body = physics.staticBody(x, y)
        val ang = Math.atan2(ny.toDouble(), nx.toDouble()).toFloat()
        physics.boxFixture(
            body, 0.012f, 0.008f, 0f, 0f, ang,
            restitution = TableTuning.TARGET_RESTITUTION, friction = 0.12f,
            category = Cat.TARGET, mask = Cat.BALL, sensor = false, target = this,
        )
    }

    fun reset() {
        pulse = 0f
    }

    fun update(dt: Float) {
        if (cooldown > 0f) cooldown -= dt
        if (pulse > 0f) pulse = (pulse - dt * 4f).coerceAtLeast(0f)
    }

    override fun onBegin(info: ContactInfo) {
        if (cooldown > 0f) return
        cooldown = TableTuning.STANDUP_COOLDOWN
        pulse = 1f
        physics.events.add(SimEvent.StandupHit(id, x, y))
    }
}

/** Drop target: drops when hit; the bank resets after all are down. */
class DropTarget(
    private val physics: PhysicsWorld,
    @JvmField val bank: Int,
    @JvmField val index: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val nx: Float,
    @JvmField val ny: Float,
    private val bankRef: DropBank,
) : ContactTarget {
    private var fixture: org.jbox2d.dynamics.Fixture
    private val body: Body
    var down = false
        private set
    var dropAnim = 0f // 0 = up, 1 = dropped
    var pulse = 0f
        private set

    init {
        body = physics.staticBody(x, y)
        val ang = Math.atan2(ny.toDouble(), nx.toDouble()).toFloat()
        fixture = physics.boxFixture(
            body, 0.011f, 0.0065f, 0f, 0f, ang,
            restitution = TableTuning.TARGET_RESTITUTION, friction = 0.12f,
            category = Cat.TARGET, mask = Cat.BALL, sensor = false, target = this,
        )
    }

    fun update(dt: Float) {
        if (pulse > 0f) pulse = (pulse - dt * 4f).coerceAtLeast(0f)
        val target = if (down) 1f else 0f
        dropAnim += (target - dropAnim) * (dt * 16f).coerceAtMost(1f)
    }

    override fun onBegin(info: ContactInfo) {
        if (down) return
        drop()
        pulse = 1f
        physics.events.add(SimEvent.DropTargetDown(bank, index, x, y))
        bankRef.onTargetDown(this)
    }

    fun drop() {
        if (down) return
        down = true
        fixture.m_filter.maskBits = 0
        fixture.refilter()
    }

    fun raise() {
        if (!down) return
        down = false
        fixture.m_filter.maskBits = Cat.BALL
        fixture.refilter()
    }
}

/** A bank of drop targets that resets as a unit. */
class DropBank(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    private val resetDelay: Float = TableTuning.DROP_BANK_RESET_DELAY,
) {
    val targets = ArrayList<DropTarget>()
    var resetTimer = -1f
        private set

    /** Cancel any pending auto-reset (used on full game reset). */
    fun cancelReset() {
        resetTimer = -1f
    }
    var pulses = 0
        private set

    fun addTarget(t: DropTarget) {
        targets.add(t)
    }

    fun onTargetDown(t: DropTarget) {
        for (i in targets.indices) {
            if (!targets[i].down) return
        }
        // All down: notify rules, then schedule the physical reset.
        physics.events.add(SimEvent.DropBankComplete(id))
        resetTimer = resetDelay
    }

    fun update(dt: Float) {
        if (resetTimer >= 0f) {
            resetTimer -= dt
            if (resetTimer < 0f) {
                reset()
            }
        }
        for (i in targets.indices) targets[i].update(dt)
    }

    /** True at the moment the bank finished resetting. */
    fun reset() {
        pulses++
        for (i in targets.indices) targets[i].raise()
    }

    val allDown: Boolean
        get() {
            for (i in targets.indices) if (!targets[i].down) return false
            return true
        }
}

/**
 * Spinner: a blade in a lane that spins as the ball passes. Revolutions are
 * estimated from ball speed through the sensor.
 */
class Spinner(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
) : ContactTarget {
    private val body: Body
    private val inside = HashSet<Int>(4)
    private val entrySpeed = HashMap<Int, Float>(4)

    /** Visual spin angle & rate. */
    var angle = 0f
    var rate = 0f
        private set

    var totalRevs = 0

    init {
        body = physics.staticBody(x, y)
        physics.boxFixture(
            body, 0.012f, 0.030f, 0f, 0f, 0f,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL, sensor = true, target = this,
        )
    }

    fun update(dt: Float) {
        rate += (0f - rate) * (dt * 1.6f).coerceAtMost(1f)
        angle += rate * dt
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        inside.add(ball.id)
        entrySpeed[ball.id] = Math.abs(ball.body.linearVelocity.y)
    }

    override fun onEnd(info: ContactInfo) {
        val ball = info.ball ?: return
        if (inside.remove(ball.id)) {
            val entry = entrySpeed.remove(ball.id) ?: return
            // Revolutions proportional to how fast the ball was moving.
            val revs = (entry * TableTuning.SPIN_REV_FACTOR).toInt().coerceIn(1, 14)
            totalRevs += revs
            rate = revs * 6f
            physics.events.add(SimEvent.SpinnerSpins(id, revs))
        }
    }
}

/**
 * One-way gate: a wall that only lets the ball through in one direction.
 * The gate opens (collision disabled) when the ball inside its trigger zone
 * moves along the allowed direction, and closes otherwise.
 */
class OneWayGate(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    /** Gate wall segment endpoints. */
    private val ax: Float, private val ay: Float,
    private val bx: Float, private val by: Float,
    /** Allowed travel direction (normalised). */
    @JvmField val dirX: Float,
    @JvmField val dirY: Float,
    /** Half-extents of the trigger zone around the wall centre. */
    private val zoneR: Float = 0.032f,
) : ContactTarget {
    private val body: Body
    private var wall: org.jbox2d.dynamics.Fixture
    private val zoneBody: Body
    private val cx = (ax + bx) * 0.5f
    private val cy = (ay + by) * 0.5f
    private var open = false
    private var openTimer = 0f

    /** Visual swing of the gate blade, 0..1. */
    var swing = 0f
        private set

    /** Reset visual + open state (new game). */
    fun reset() {
        open = false
        openTimer = 0f
        swing = 0f
    }

    init {
        body = physics.staticBody(0f, 0f)
        wall = physics.wallFixture(body, floatArrayOf(ax, ay, bx, by), restitution = 0.1f, friction = 0.05f, category = Cat.GATE, mask = Cat.BALL)
        // Zone sits on the approach side of the wall so the gate is open
        // well before the ball could reach the blade.
        zoneBody = physics.staticBody(cx - dirX * 0.028f, cy - dirY * 0.028f)
        physics.boxFixture(
            zoneBody, zoneR + 0.012f, zoneR + 0.012f, 0f, 0f, 0f,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL, sensor = true, target = this,
        )
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        val v = ball.body.linearVelocity
        val along = v.x * dirX + v.y * dirY
        if (along > 0.15f) setOpen(true)
    }

    fun update(dt: Float) {
        if (openTimer > 0f) {
            openTimer -= dt
            if (openTimer <= 0f) setOpen(false)
        }
        swing += ((if (open) 1f else 0f) - swing) * (dt * 20f).coerceAtMost(1f)
    }

    private fun setOpen(value: Boolean) {
        if (open == value) {
            if (value) openTimer = 0.18f
            return
        }
        open = value
        if (value) {
            wall.m_filter.maskBits = 0
            openTimer = 0.18f
            physics.events.add(SimEvent.GatePassed(id, cx, cy))
        } else {
            wall.m_filter.maskBits = Cat.BALL
        }
        wall.refilter()
    }

    /** Force gate state (used when serving balls). */
    fun force(open: Boolean) {
        setOpen(open)
    }
}

/**
 * Scoop / capture hole: swallows a ball when it enters slowly enough or from
 * the ramp. The rules layer decides what happens (lock, jackpot, eject).
 */
class Scoop(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val r: Float = 0.024f,
) : ContactTarget {
    private val body: Body
    private val inside = HashSet<Int>(4)

    /** Enabled: captures balls; disabled: purely decorative pass-through. */
    var enabled = true

    /** Time since a ball was captured, for FX. */
    var captureFlash = 0f
        private set

    init {
        body = physics.staticBody(x, y)
        physics.circleFixture(
            body, 0f, 0f, r,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL or Cat.BALL_RAMP, sensor = true, target = this,
        )
    }

    fun reset() {
        captureFlash = 0f
    }

    fun update(dt: Float) {
        if (captureFlash > 0f) captureFlash = (captureFlash - dt * 2.2f).coerceAtLeast(0f)
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        inside.add(ball.id)
        if (enabled) {
            captureFlash = 1f
            physics.events.add(SimEvent.HoleCapture(ball.id, id))
        }
    }

    override fun onEnd(info: ContactInfo) {
        info.ball?.let { inside.remove(it.id) }
    }
}

/**
 * Rectangular shot sensor: emits [SimEvent.ShotLane] when a ball enters,
 * carrying position and vertical velocity so the game can implement layer
 * switches (ramp entry/exit) and combo detection.
 */
class ShotSensor(
    private val physics: PhysicsWorld,
    @JvmField val id: Int,
    @JvmField val x: Float,
    @JvmField val y: Float,
    w: Float,
    h: Float,
    angle: Float = 0f,
) : ContactTarget {
    private val body: Body

    init {
        body = physics.staticBody(x, y)
        physics.boxFixture(
            body, w * 0.5f, h * 0.5f, 0f, 0f, angle,
            restitution = 0f, friction = 0f,
            category = Cat.SENSOR, mask = Cat.BALL or Cat.BALL_RAMP, sensor = true, target = this,
        )
    }

    override fun onBegin(info: ContactInfo) {
        val ball = info.ball ?: return
        physics.events.add(SimEvent.ShotLane(id, ball.id, ball.x, ball.y, ball.body.linearVelocity.y))
    }
}
