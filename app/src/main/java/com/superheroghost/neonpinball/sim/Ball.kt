package com.superheroghost.neonpinball.sim

import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.Fixture
import org.jbox2d.dynamics.FixtureDef

/** Lifecycle state of a ball. */
enum class BallState {
    /** Sitting on the plunger, waiting to be launched. */
    ON_PLUNGER,

    /** Live on the playfield. */
    LIVE,

    /** Riding the raised ramp; only ramp-plane geometry collides. */
    ON_RAMP,

    /** Swallowed by the scoop (being locked or scored). */
    CAPTURED,

    /** Held in the lock (not in the world). */
    LOCKED,

    /** Below the drain line; about to be removed. */
    DRAINED,
}

/**
 * A pinball. Owns its jBox2D body plus render interpolation state and the
 * ramp-plane flag which drives collision filtering.
 */
class Ball(
    @JvmField val id: Int,
    private val physics: PhysicsWorld,
) {
    var body: Body
    var fixture: Fixture
    var state = BallState.ON_PLUNGER

    // Render interpolation: previous and current physics transform.
    var prevX = 0f
    var prevY = 0f
    var currX = 0f
    var currY = 0f
    var spin = 0f // accumulated visual spin for the ball highlight

    var onRamp = false
        private set

    /** Contact impact strength accumulated this step (0..1) for audio. */
    var lastImpact = 0f

    val x: Float get() = currX
    val y: Float get() = currY

    val speed: Float
        get() = body.linearVelocity.length()

    init {
        val def = BodyDef()
        def.type = BodyType.DYNAMIC
        def.position.set(0f, 0f)
        def.bullet = true // CCD against static geometry: no tunnelling.
        def.linearDamping = TableTuning.BALL_DAMPING
        def.angularDamping = 0.06f
        def.allowSleep = false
        body = physics.world.createBody(def)
        val shape = CircleShape()
        shape.m_radius = TableTuning.BALL_R
        val fd = FixtureDef()
        fd.shape = shape
        fd.density = TableTuning.BALL_DENSITY
        fd.friction = TableTuning.BALL_FRICTION
        fd.restitution = TableTuning.BALL_RESTITUTION
        fd.filter.categoryBits = Cat.BALL
        fd.filter.maskBits = Cat.BALL_MASK
        fd.userData = this
        fixture = body.createFixture(fd)
        body.isActive = false
    }

    fun place(x: Float, y: Float, vx: Float = 0f, vy: Float = 0f) {
        if (!body.isActive) body.isActive = true
        body.setTransform(Vec2(x, y), 0f)
        body.setLinearVelocity(Vec2(vx, vy))
        body.setAngularVelocity(0f)
        prevX = x; prevY = y; currX = x; currY = y
    }

    fun setVelocity(vx: Float, vy: Float) {
        body.setLinearVelocity(Vec2(vx, vy))
    }

    fun addVelocity(dvx: Float, dvy: Float) {
        val v = body.linearVelocity
        body.setLinearVelocity(Vec2(v.x + dvx, v.y + dvy))
    }

    fun removeFromWorld() {
        body.isActive = false
    }

    fun restoreToWorld() {
        body.isActive = true
    }

    /** Switch collision plane. Ramp balls only see ramp geometry. */
    fun setRampMode(on: Boolean) {
        if (onRamp == on) return
        onRamp = on
        val f = fixture.m_filter
        if (on) {
            f.categoryBits = Cat.BALL_RAMP
            f.maskBits = Cat.BALL_RAMP_MASK
        } else {
            f.categoryBits = Cat.BALL
            f.maskBits = Cat.BALL_MASK
        }
        fixture.refilter()
    }

    /** Called once per fixed step before the world steps: cache transform. */
    fun preStep() {
        prevX = currX
        prevY = currY
    }

    /** Called after the world steps: read new transform. */
    fun postStep() {
        val p = body.position
        currX = p.x
        currY = p.y
    }

    /** Interpolated render position. */
    fun renderX(alpha: Float): Float = prevX + (currX - prevX) * alpha
    fun renderY(alpha: Float): Float = prevY + (currY - prevY) * alpha
}
