package com.superheroghost.neonpinball.sim

import org.jbox2d.collision.shapes.ChainShape
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.collision.shapes.MassData
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.joints.RevoluteJoint
import org.jbox2d.dynamics.joints.RevoluteJointDef

/**
 * Dynamic flipper driven by a revolute joint motor. Using a real motor (rather
 * than a kinematic body) gives physically correct momentum transfer: the ball
 * receives impulse proportional to how early in the stroke it is caught, and
 * trapped cradle shots behave like the real machine.
 *
 * Left flippers sweep from REST (pointing down towards the drain) up to UP.
 * Right flippers are mirrored.
 */
class Flipper(
    private val physics: PhysicsWorld,
    @JvmField val pivotX: Float,
    @JvmField val pivotY: Float,
    @JvmField val left: Boolean,
) : ContactTarget {
    var body: Body
    var joint: RevoluteJoint
    private var pressed = false
    var angle = 0f // current joint angle, radians

    /** Visual: charge 0..1 while up. */
    var activation = 0f
    private set

    /** True during the upward sweep - drives flipper FX. */
    var sweepingUp = false
    private set

    /** Accumulated hit power for FX routing. */
    var lastHitPower = 0f

    private val restAngle: Float
    private val upAngle: Float

    init {
        val t = TableTuning
        restAngle = if (left) -t.FLIPPER_REST_ANGLE else Math.PI.toFloat() + t.FLIPPER_REST_ANGLE
        upAngle = if (left) t.FLIPPER_UP_ANGLE else Math.PI.toFloat() - t.FLIPPER_UP_ANGLE

        val def = BodyDef()
        def.type = BodyType.DYNAMIC
        def.position.set(pivotX, pivotY)
        def.angle = restAngle
        def.allowSleep = false
        def.bullet = true // CCD against fast balls
        body = physics.world.createBody(def)

        // Capsule: base circle + tip circle + a chain outline between them.
        // NOTE: a thin polygon is impossible here - jBox2D welds polygon
        // vertices closer than ~5cm together, which degenerates a
        // flipper-sized quad into a 1x1m box. Chains do not weld, so the
        // tapered sides are chains and the mass is set analytically below.
        val len = t.FLIPPER_LEN
        val rb = t.FLIPPER_BASE_R
        val rt = t.FLIPPER_TIP_R

        val baseCircle = CircleShape()
        baseCircle.m_radius = rb
        val baseDef = FixtureDef()
        baseDef.shape = baseCircle
        baseDef.friction = 0.9f // grip the ball for flip & control
        baseDef.restitution = 0.42f
        baseDef.filter.categoryBits = Cat.FLIPPER
        baseDef.filter.maskBits = Cat.BALL
        body.createFixture(baseDef).userData = this

        val tipCircle = CircleShape()
        tipCircle.m_p.set(len, 0f)
        tipCircle.m_radius = rt
        val tipDef = FixtureDef()
        tipDef.shape = tipCircle
        tipDef.friction = 0.9f
        tipDef.restitution = 0.42f
        tipDef.filter.categoryBits = Cat.FLIPPER
        tipDef.filter.maskBits = Cat.BALL
        body.createFixture(tipDef).userData = this

        // Tapered outline sampled every quarter length.
        val outline = ArrayList<Vec2>(10)
        for (i in 0..4) {
            val f = i / 4f
            outline.add(Vec2(len * f, (rb + (rt - rb) * f) * 0.97f))
        }
        for (i in 4 downTo 0) {
            val f = i / 4f
            outline.add(Vec2(len * f, -(rb + (rt - rb) * f) * 0.97f))
        }
        val chain = ChainShape()
        chain.createChain(outline.toTypedArray(), outline.size)
        val chainDef = FixtureDef()
        chainDef.shape = chain
        chainDef.friction = 0.9f
        chainDef.restitution = 0.42f
        chainDef.filter.categoryBits = Cat.FLIPPER
        chainDef.filter.maskBits = Cat.BALL
        body.createFixture(chainDef).userData = this

        // The flipper acts as a velocity source, like a real machine: its
        // centre of mass is placed at the pivot (which also perfectly
        // conditions the joint solver - an offset COM with small inertia
        // makes jBox2D's revolute limit solve explode) and its inertia is
        // set high enough that a 45g ball cannot stall the stroke.
        // Effective mass at the contact point = I / r^2 ~ 0.7kg.
        val density = t.FLIPPER_DENSITY
        val mRect = density * len * (rb + rt)
        val mBase = (Math.PI * rb * rb * density).toFloat()
        val mTip = (Math.PI * rt * rt * density).toFloat()
        val mass = mRect + mBase + mTip
        val massData = MassData()
        massData.mass = mass
        massData.center.set(0f, 0f)
        massData.I = t.FLIPPER_INERTIA
        body.setMassData(massData)

        val jd = RevoluteJointDef()
        jd.bodyA = physics.staticBody(pivotX, pivotY)
        jd.bodyB = body
        jd.localAnchorA.set(0f, 0f)
        jd.localAnchorB.set(0f, 0f)
        jd.enableLimit = true
        if (left) {
            jd.lowerAngle = restAngle
            jd.upperAngle = upAngle
        } else {
            jd.lowerAngle = upAngle
            jd.upperAngle = restAngle
        }
        jd.enableMotor = true
        jd.motorSpeed = -t.FLIPPER_DOWN_SPEED
        jd.maxMotorTorque = t.FLIPPER_TORQUE * 0.30f // holding torque
        joint = physics.world.createJoint(jd) as RevoluteJoint
        body.setAngularVelocity(0f)
    }

    fun setPressed(p: Boolean) {
        if (pressed == p) return
        pressed = p
        if (p) {
            joint.motorSpeed = if (left) TableTuning.FLIPPER_UP_SPEED else -TableTuning.FLIPPER_UP_SPEED
            joint.maxMotorTorque = TableTuning.FLIPPER_TORQUE
            sweepingUp = true
        } else {
            joint.motorSpeed = if (left) -TableTuning.FLIPPER_DOWN_SPEED else TableTuning.FLIPPER_DOWN_SPEED
            joint.maxMotorTorque = TableTuning.FLIPPER_TORQUE * 0.05f
        }
    }

    val isPressed: Boolean get() = pressed

    fun update(dt: Float) {
        angle = body.angle
        activation += ((if (pressed) 1f else 0f) - activation) * (dt * 18f).coerceAtMost(1f)
        if (sweepingUp) {
            val err = if (left) (upAngle - angle) else (angle - upAngle)
            if (!pressed || err < 0.05f) sweepingUp = false
        }

        // Soft landing near either limit so the position solver never has to
        // absorb the full stroke speed (it would overshoot by ~0.2 rad).
        val t = TableTuning
        val stopZone = 0.28f
        if (pressed) {
            val dist = if (left) (upAngle - angle) else (angle - upAngle)
            val speed = if (dist < stopZone) {
                (t.FLIPPER_UP_SPEED * (dist / stopZone)).coerceIn(4f, t.FLIPPER_UP_SPEED)
            } else {
                t.FLIPPER_UP_SPEED
            }
            joint.motorSpeed = if (left) speed else -speed
            joint.maxMotorTorque = t.FLIPPER_TORQUE
        } else {
            val dist = if (left) (angle - restAngle) else (restAngle - angle)
            val speed = if (dist < stopZone) {
                (t.FLIPPER_DOWN_SPEED * (dist / stopZone)).coerceIn(2f, t.FLIPPER_DOWN_SPEED)
            } else {
                t.FLIPPER_DOWN_SPEED
            }
            joint.motorSpeed = if (left) -speed else speed
            joint.maxMotorTorque = t.FLIPPER_TORQUE * 0.05f
        }
    }

    /** Tip position for FX. */
    fun tip(out: Vec2): Vec2 {
        val c = Math.cos(angle.toDouble()).toFloat()
        val s = Math.sin(angle.toDouble()).toFloat()
        out.set(
            pivotX + TableTuning.FLIPPER_LEN * c,
            pivotY + TableTuning.FLIPPER_LEN * s,
        )
        return out
    }

    override fun onBegin(info: ContactInfo) {
        val power = (info.speed / 4.5f).coerceIn(0f, 1f)
        if (power > 0.12f) {
            lastHitPower = power
            physics.events.add(SimEvent.FlipperHit(left, power, info.x, info.y))
        }
    }
}
