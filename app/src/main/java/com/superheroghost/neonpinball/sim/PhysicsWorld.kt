package com.superheroghost.neonpinball.sim

import org.jbox2d.callbacks.ContactFilter
import org.jbox2d.callbacks.ContactImpulse
import org.jbox2d.callbacks.ContactListener
import org.jbox2d.collision.Manifold
import org.jbox2d.collision.WorldManifold
import org.jbox2d.collision.shapes.ChainShape
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Settings
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.Fixture
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact

/** Collision categories (16 bits available). */
object Cat {
    const val WALL = 0x0001
    const val BALL = 0x0002
    const val FLIPPER = 0x0004
    const val BUMPER = 0x0008
    const val TARGET = 0x0010
    const val SENSOR = 0x0020
    const val RAMP = 0x0040
    const val GATE = 0x0080
    const val BALL_RAMP = 0x0100

    /** What a normal playfield ball collides with. */
    const val BALL_MASK =
        (WALL or BALL or FLIPPER or BUMPER or TARGET or SENSOR or GATE)

    /** What a ball riding the ramp collides with. */
    const val BALL_RAMP_MASK = (RAMP or SENSOR or BALL_RAMP)
}

/** Summary of a contact handed to game elements. */
class ContactInfo {
    var ball: Ball? = null
    var fixture: Fixture? = null // the other fixture
    var nx = 0f
    var ny = 0f // contact normal pointing from other fixture towards ball
    var speed = 0f // approach speed along normal
    var x = 0f
    var y = 0f

    fun set(b: Ball?, other: Fixture?, normalX: Float, normalY: Float, approach: Float, px: Float, py: Float) {
        ball = b
        fixture = other
        nx = normalX
        ny = normalY
        speed = approach
        x = px
        y = py
    }
}

/** Anything that wants raw contact callbacks implements this. */
interface ContactTarget {
    fun onBegin(info: ContactInfo) {}
    fun onEnd(info: ContactInfo) {}
}

/**
 * Thin wrapper around jBox2D isolating the rest of the game from the library.
 * Owns the world, the fixed timestep, contact routing and layer switching.
 */
class PhysicsWorld {
    val world = World(Vec2(0f, -TableTuning.GRAVITY))
    private val accumulator = FloatArray(1)
    private val contactInfo = ContactInfo()
    private val tmpV = Vec2()

    /** Populated after each [step]. */
    val events = SimEventQueue()

    /** Bodies queued for deferred destruction (never destroy mid-step). */
    private val destroyQueue = ArrayList<Body>(8)
    private var stepping = false

    // Scratch objects to avoid allocation in contact callbacks.
    private val relV = Vec2()
    private val worldManifold = WorldManifold()

    init {
        // jBox2D's default polygon/chain radius is 2 * linearSlop = 1cm, which
        // makes every wall 2cm thick - far too fat for a real-scale pinball
        // playfield (the shooter lane would be narrower than the ball).
        // Thin shapes down; the solver keeps the default slop.
        Settings.polygonRadius = 0.0015f

        world.setContactListener(object : ContactListener {
            override fun beginContact(contact: Contact) = routeContact(contact, true)
            override fun endContact(contact: Contact) = routeContact(contact, false)
            override fun preSolve(contact: Contact, oldManifold: Manifold) {}
            override fun postSolve(contact: Contact, impulse: ContactImpulse) {}
        })
        world.setContactFilter(object : ContactFilter() {
            override fun shouldCollide(fixtureA: Fixture, fixtureB: Fixture): Boolean {
                val a = fixtureA.m_filter
                val b = fixtureB.m_filter
                return (a.maskBits and b.categoryBits) != 0 && (b.maskBits and a.categoryBits) != 0
            }
        })
    }

    fun fixedStep(dt: Float): Boolean {
        accumulator[0] += dt
        var stepped = false
        var steps = 0
        while (accumulator[0] >= TableTuning.FIXED_DT && steps < TableTuning.MAX_SUBSTEPS) {
            accumulator[0] -= TableTuning.FIXED_DT
            internalStep()
            stepped = true
            steps++
        }
        if (steps == TableTuning.MAX_SUBSTEPS) {
            // Falling behind: drop time to avoid spiral of death.
            accumulator[0] = 0f
        }
        return stepped
    }

    private fun internalStep() {
        stepping = true
        try {
            world.step(TableTuning.FIXED_DT, TableTuning.VELOCITY_ITERATIONS, TableTuning.POSITION_ITERATIONS)
        } finally {
            stepping = false
        }
        flushDestroyQueue()
    }

    /** Step exactly once (used by tests and deterministic replay). */
    fun stepOnce() {
        internalStep()
    }

    fun queueDestroy(body: Body?) {
        if (body == null) return
        if (!stepping) {
            world.destroyBody(body)
        } else {
            destroyQueue.add(body)
        }
    }

    private fun flushDestroyQueue() {
        for (i in destroyQueue.indices) world.destroyBody(destroyQueue[i])
        destroyQueue.clear()
    }

    // ------------------------------------------------------------ factories

    fun staticBody(x: Float, y: Float, angle: Float = 0f): Body {
        val def = BodyDef()
        def.type = BodyType.STATIC
        def.position.set(x, y)
        def.angle = angle
        return world.createBody(def)
    }

    fun wallFixture(body: Body, points: FloatArray, restitution: Float = TableTuning.WALL_RESTITUTION, friction: Float = 0.1f, category: Int = Cat.WALL, mask: Int = Cat.BALL or Cat.FLIPPER): Fixture {
        val chain = ChainShape()
        val verts = arrayOfNulls<Vec2>(points.size / 2)
        for (i in verts.indices) verts[i] = Vec2(points[i * 2] - body.position.x, points[i * 2 + 1] - body.position.y)
        chain.createChain(verts, verts.size)
        val fd = FixtureDef()
        fd.shape = chain
        fd.friction = friction
        fd.restitution = restitution
        fd.filter.categoryBits = category
        fd.filter.maskBits = mask
        return body.createFixture(fd)
    }

    fun circleFixture(body: Body, localX: Float, localY: Float, r: Float, restitution: Float, friction: Float, category: Int, mask: Int, sensor: Boolean = false, target: ContactTarget? = null, density: Float = 0f): Fixture {
        val shape = CircleShape()
        shape.m_p.set(localX, localY)
        shape.m_radius = r
        val fd = FixtureDef()
        fd.shape = shape
        fd.isSensor = sensor
        fd.friction = friction
        fd.restitution = restitution
        fd.density = density
        fd.filter.categoryBits = category
        fd.filter.maskBits = mask
        val f = body.createFixture(fd)
        if (target != null) f.userData = target
        return f
    }

    fun boxFixture(body: Body, hx: Float, hy: Float, localX: Float, localY: Float, angle: Float, restitution: Float, friction: Float, category: Int, mask: Int, sensor: Boolean = false, target: ContactTarget? = null): Fixture {
        val shape = PolygonShape()
        shape.setAsBox(hx, hy, Vec2(localX, localY), angle)
        val fd = FixtureDef()
        fd.shape = shape
        fd.isSensor = sensor
        fd.friction = friction
        fd.restitution = restitution
        fd.filter.categoryBits = category
        fd.filter.maskBits = mask
        val f = body.createFixture(fd)
        if (target != null) f.userData = target
        return f
    }

    fun polygonFixture(body: Body, verts: FloatArray, restitution: Float, friction: Float, category: Int, mask: Int, target: ContactTarget? = null, density: Float = 0f): Fixture {
        val shape = PolygonShape()
        val v = arrayOfNulls<Vec2>(verts.size / 2)
        for (i in v.indices) v[i] = Vec2(verts[i * 2], verts[i * 2 + 1])
        shape.set(v, v.size)
        val fd = FixtureDef()
        fd.shape = shape
        fd.friction = friction
        fd.restitution = restitution
        fd.density = density
        fd.filter.categoryBits = category
        fd.filter.maskBits = mask
        val f = body.createFixture(fd)
        if (target != null) f.userData = target
        return f
    }

    // ------------------------------------------------------------ contacts

    private fun routeContact(contact: Contact, begin: Boolean) {
        val fa = contact.fixtureA
        val fb = contact.fixtureB
        val ua = fa.userData
        val ub = fb.userData
        if (ua == null && ub == null) return

        val ballA = ua as? Ball
        val ballB = ub as? Ball
        if (ballA == null && ballB == null) {
            // Non-ball contact pairs (e.g. flipper vs wall) are ignored.
            return
        }

        // Fill contact info from the manifold. Sensor contacts have no
        // manifold points; fall back to the midpoint of the two bodies.
        contact.getWorldManifold(worldManifold)
        val wm = worldManifold
        val px: Float
        val py: Float
        if (contact.manifold.pointCount > 0) {
            px = wm.points[0].x
            py = wm.points[0].y
        } else {
            val a = fa.body.position
            val b = fb.body.position
            px = (a.x + b.x) * 0.5f
            py = (a.y + b.y) * 0.5f
        }

        if (ballA != null) {
            // Fixture A is the ball; the contact target (if any) is B's data.
            dispatch(ub as? ContactTarget, ballA, fb, wm.normal.x, wm.normal.y, px, py, contact, begin)
        }
        if (ballB != null) {
            // Fixture B is the ball; the target is A's data. Flip the normal
            // so it points at ballB.
            dispatch(ua as? ContactTarget, ballB, fa, -wm.normal.x, -wm.normal.y, px, py, contact, begin)
        }
    }

    private fun dispatch(
        target: ContactTarget?,
        ball: Ball,
        other: Fixture,
        nx: Float,
        ny: Float,
        px: Float,
        py: Float,
        contact: Contact,
        begin: Boolean,
    ) {
        if (target == null) return
        // Approach speed: relative velocity of ball vs other body along normal.
        val bodyVel = ball.body.linearVelocity
        val otherBody = other.body
        if (otherBody.type == BodyType.STATIC) {
            relV.set(bodyVel)
        } else {
            tmpV.set(otherBody.linearVelocity)
            relV.set(bodyVel.x - tmpV.x, bodyVel.y - tmpV.y)
        }
        val approach = relV.x * nx + relV.y * ny
        contactInfo.set(ball, other, nx, ny, approach, px, py)
        if (begin) target.onBegin(contactInfo) else target.onEnd(contactInfo)
    }

    companion object {
        fun velocityOf(ball: Ball): Vec2 = ball.body.linearVelocity
    }
}
