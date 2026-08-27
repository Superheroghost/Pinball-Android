package com.superheroghost.neonpinball.sim

import org.jbox2d.common.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Identifiers for every named element/light on the table. The rules engine and
 * renderer use these to communicate about table state without holding
 * references to each other.
 */
object Ids {
    // Bumpers
    const val BUMP_A = 0
    const val BUMP_B = 1
    const val BUMP_C = 2

    // Slingshots
    const val SLING_L = 0
    const val SLING_R = 1

    // Rollover lanes
    const val LANE_N = 0
    const val LANE_E = 1
    const val LANE_X = 2
    const val INLANE_L = 3
    const val INLANE_R = 4
    const val OUTLANE_L = 5
    const val OUTLANE_R = 6

    // Stand-ups (ramp mouth guards)
    const val STANDUP_L = 0
    const val STANDUP_R = 1

    // Drop bank
    const val DROP_BANK = 0

    // Spinner
    const val SPINNER = 0

    // Gates
    const val GATE_SHOOTER = 0

    // Scoop
    const val SCOOP = 0

    // Shot lanes (combo shot ids)
    const val SHOT_ORBIT = 0
    const val SHOT_RAMP = 1
    const val SHOT_MOUTH_EXIT = 2
    const val SHOT_DROP = 3
    const val SHOT_SPIN = 4
    const val SHOT_STANDUP = 5
    const val SHOT_BUMP = 6
    const val SHOT_SCOOP = 7

    // Lights (rule board)
    const val L_ORBIT = 0
    const val L_RAMP = 1
    const val L_LOCK = 2
    const val L_JACKPOT = 3
    const val L_SUPER = 4
    const val L_SURGE_L = 5
    const val L_SURGE_R = 6
    const val L_MODE = 7
    const val L_MULTIBALL = 8
    const val L_SKILL = 9
    const val L_BONUS_X = 10
    const val L_EXTRABALL = 11
    const val L_BUMPA = 12
    const val L_BUMPB = 13
    const val L_BUMPC = 14
}

/**
 * Builds the entire table: walls, arcs, elements, sensors and gates.
 *
 * Layout (y up, metres, playfield 0.514 x 1.067):
 *  - Shooter lane on the right; a full plunge carries the ball up and onto the
 *    top arch where it rides counter-clockwise over the top of the table.
 *  - A one-way gate seals the shooter exit so returning balls cannot fall back
 *    into the lane: they get deflected into the three N-E-X rollover lanes.
 *    A soft plunge that just clears the gate drops into the lit lane = skill shot.
 *  - Left orbit: entrance below the guide post, spinner in the ascent lane,
 *    apex sensor at the top. Completing the orbit returns the ball over the
 *    top-right and down through the lanes.
 *  - Centre ramp: mouth between two stand-up guards (fed by a centre flipper
 *    shot), tube climbing up-left on its own collision plane, delivering to
 *    the capture scoop at the top-left.
 *  - Three pop bumpers centre-left, drop target bank on the right island.
 *  - Slingshots, inlanes, outlanes and two flippers at the bottom.
 */
class TableGeometry(private val physics: PhysicsWorld, debugStage: Int = 0) {
    val bumpers = ArrayList<Bumper>(3)
    val slings = ArrayList<Slingshot>(2)
    val rollovers = HashMap<Int, Rollover>()
    val standups = HashMap<Int, Standup>()
    lateinit var dropBank: DropBank
    lateinit var spinner: Spinner
    lateinit var shooterGate: OneWayGate
    lateinit var scoop: Scoop
    val shotSensors = HashMap<Int, ShotSensor>()
    lateinit var flipperL: Flipper
        private set
    lateinit var flipperR: Flipper
        private set

    /** Render data: outer wall polyline (arch included). */
    val outerBoundary = ArrayList<Vec2>()

    /** Render data: ramp tube centre path. */
    val rampPath = ArrayList<Vec2>()

    /** Render data: orbit guide polyline. */
    val orbitGuide = ArrayList<Vec2>()

    /** Render data: every recorded wall run (for metallic rendering). */
    val wallRuns = ArrayList<FloatArray>()

    private fun wall(points: FloatArray, restitution: Float = TableTuning.WALL_RESTITUTION, record: Boolean = true) {
        for (i in 2 until points.size step 2) {
            val dx = points[i] - points[i - 2]
            val dy = points[i + 1] - points[i - 1]
            if (dx * dx + dy * dy < 0.005f * 0.005f) {
                throw IllegalStateException("wall segment too short: (${points[i - 2]},${points[i - 1]})-(${points[i]},${points[i + 1]})")
            }
        }
        val body = physics.staticBody(0f, 0f)
        physics.wallFixture(body, points, restitution = restitution)
        if (record) wallRuns.add(points)
    }

    private fun arcPoints(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, segments: Int): FloatArray {
        val pts = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val a = a0 + (a1 - a0) * i / segments
            pts[i * 2] = cx + r * cos(a)
            pts[i * 2 + 1] = cy + r * sin(a)
        }
        return pts
    }

    private fun post(x: Float, y: Float, r: Float = 0.008f) {
        val b = physics.staticBody(x, y)
        physics.circleFixture(b, 0f, 0f, r, TableTuning.POST_RESTITUTION, 0.1f, Cat.WALL, Cat.BALL)
    }

    /** Debug: limit construction to first N stages (0=all) for bisection. */
    private val buildStage = debugStage

    init {
        val t = TableTuning
        val stage = { n: Int -> buildStage == 0 || buildStage >= n }

        if (stage(1)) {
        // ------------------------------------------------ outer boundary
        val arch = arcPoints(t.TABLE_W * 0.5f, t.ARCH_CY, t.ARCH_R, 0f, Math.PI.toFloat(), 44)
        wall(arch)
        outerBoundary.clear()
        for (i in arch.indices step 2) outerBoundary.add(Vec2(arch[i], arch[i + 1]))

        // Side walls up to the arch springing points.
        wall(floatArrayOf(0f, 0.055f, 0f, t.ARCH_CY))
        wall(floatArrayOf(t.TABLE_W, 0.055f, t.TABLE_W, t.ARCH_CY))
        // Playfield floor: two segments with the DRAIN GAP between the outer
        // funnel ends. Balls over the gap fall through to DRAIN_Y and drain.
        val drainGapL = 0.118f
        val drainGapR = t.SHOOTER_X_INNER - 0.118f
        wall(floatArrayOf(0f, 0.055f, drainGapL, 0.055f), record = false)
        wall(floatArrayOf(drainGapR, 0.055f, t.SHOOTER_X_INNER, 0.055f), record = false)

        // Shooter lane: inner wall + floor.
        wall(floatArrayOf(t.SHOOTER_X_INNER, 0.055f, t.SHOOTER_X_INNER, 0.870f))
        wall(floatArrayOf(t.SHOOTER_X_INNER, 0.055f, t.TABLE_W, 0.055f), record = false)
        }

        if (stage(2)) {
        // ------------------------------------------------ left orbit guide
        // Vertical guide then arc from 180deg to 55deg over the top. The lane
        // between this guide and the left outer wall is the orbit; the guide's
        // lower tip is rounded by a post so balls split cleanly between the
        // orbit and the interior.
        val guide = arcPoints(t.TABLE_W * 0.5f, t.ARCH_CY, t.ORBIT_GUIDE_R, Math.PI.toFloat(), (55f * Math.PI / 180f).toFloat(), 30)
        val guidePts = FloatArray(guide.size + 2)
        guidePts[0] = t.TABLE_W * 0.5f - t.ORBIT_GUIDE_R
        guidePts[1] = 0.555f
        System.arraycopy(guide, 0, guidePts, 2, guide.size)
        wall(guidePts)
        orbitGuide.clear()
        for (i in guidePts.indices step 2) orbitGuide.add(Vec2(guidePts[i], guidePts[i + 1]))
        post(guidePts[0], 0.555f, 0.008f)

        // Orbit apex shot sensor (playfield balls riding the orbit).
        shotSensors[Ids.SHOT_ORBIT] = ShotSensor(physics, Ids.SHOT_ORBIT, t.TABLE_W * 0.5f, t.ARCH_CY + t.ORBIT_GUIDE_R + 0.026f, 0.075f, 0.02f)

        // Spinner in the orbit ascent lane.
        spinner = Spinner(physics, Ids.SPINNER, 0.0345f, 0.615f)
        }

        if (stage(3)) {
        // ------------------------------------------------ top lanes (N-E-X)
        val x0 = 0.300f
        val laneW = 0.0365f
        val divW = 0.0075f
        val yBot = 0.838f
        val yTop = 0.905f
        wall(floatArrayOf(x0 - divW, yBot - 0.075f, x0 - divW, yTop))
        wall(floatArrayOf(x0 + laneW, yBot, x0 + laneW, yTop))
        wall(floatArrayOf(x0 + 2 * laneW + divW, yBot, x0 + 2 * laneW + divW, yTop))
        wall(floatArrayOf(x0 + 3 * laneW + 2 * divW, yBot - 0.055f, x0 + 3 * laneW + 2 * divW, yTop))
        // Rounded divider tops.
        post(x0 + laneW, yTop, 0.0037f)
        post(x0 + 2 * laneW + divW, yTop, 0.0037f)
        post(x0 - divW, yTop, 0.0037f)
        // Bottom deflector guiding lane exits into the playfield.
        wall(floatArrayOf(x0 - divW, yBot - 0.075f, x0 + 0.020f, yBot - 0.100f))
        rollovers[Ids.LANE_N] = Rollover(physics, Ids.LANE_N, x0 + laneW * 0.5f, yBot + 0.032f, laneW, 0.02f)
        rollovers[Ids.LANE_E] = Rollover(physics, Ids.LANE_E, x0 + laneW * 1.5f + divW, yBot + 0.032f, laneW, 0.02f)
        rollovers[Ids.LANE_X] = Rollover(physics, Ids.LANE_X, x0 + laneW * 2.5f + 2 * divW, yBot + 0.032f, laneW, 0.02f)
        }

        if (stage(4)) {
        // ------------------------------------------------ right island + drops
        // Island attached to the shooter wall: sloped top deflects orbit
        // returns and lane balls into the playfield; left face carries the
        // drop target bank.
        wall(
            floatArrayOf(
                t.SHOOTER_X_INNER, 0.862f,
                0.436f, 0.800f,
                0.418f, 0.740f,
                0.414f, 0.700f,
            ),
        )
        wall(
            floatArrayOf(
                0.414f, 0.592f,
                0.428f, 0.560f,
                0.452f, 0.551f,
                t.SHOOTER_X_INNER, 0.549f,
            ),
        )
        dropBank = DropBank(physics, Ids.DROP_BANK)
        for (i in 0..2) {
            val ty = 0.615f + i * 0.030f
            dropBank.addTarget(DropTarget(physics, Ids.DROP_BANK, i, 0.402f, ty, -1f, 0f, dropBank))
        }
        shotSensors[Ids.SHOT_DROP] = ShotSensor(physics, Ids.SHOT_DROP, 0.386f, 0.645f, 0.018f, 0.10f)
        }

        if (stage(5)) {
        // ------------------------------------------------ bumpers
        bumpers.add(Bumper(physics, Ids.BUMP_A, 0.170f, 0.660f))
        bumpers.add(Bumper(physics, Ids.BUMP_B, 0.272f, 0.660f))
        bumpers.add(Bumper(physics, Ids.BUMP_C, 0.221f, 0.754f))
        }

        if (stage(6)) {
        // ------------------------------------------------ centre ramp
        val path = floatArrayOf(
            0.255f, 0.548f,
            0.236f, 0.578f,
            0.205f, 0.612f,
            0.172f, 0.655f,
            0.150f, 0.700f,
            0.136f, 0.752f,
            0.130f, 0.806f,
            0.129f, 0.858f,
            0.134f, 0.898f,
            0.150f, 0.925f,
        )
        rampPath.clear()
        for (i in path.indices step 2) rampPath.add(Vec2(path[i], path[i + 1]))
        buildTube(path, 0.018f)

        // Mouth flare guides (playfield plane).
        wall(floatArrayOf(0.218f, 0.500f, 0.2335f, 0.5455f))
        wall(floatArrayOf(0.292f, 0.500f, 0.2765f, 0.5455f))
        standups[Ids.STANDUP_L] = Standup(physics, Ids.STANDUP_L, 0.2165f, 0.492f, 0.62f, -0.78f)
        standups[Ids.STANDUP_R] = Standup(physics, Ids.STANDUP_R, 0.2935f, 0.492f, -0.62f, -0.78f)

        // Ramp entry sensor (in the mouth) and exit sensor (below it).
        shotSensors[Ids.SHOT_RAMP] = ShotSensor(physics, Ids.SHOT_RAMP, 0.255f, 0.540f, 0.040f, 0.020f)
        shotSensors[Ids.SHOT_MOUTH_EXIT] = ShotSensor(physics, Ids.SHOT_MOUTH_EXIT, 0.255f, 0.522f, 0.040f, 0.016f)

        // Scoop at the top of the tube.
        scoop = Scoop(physics, Ids.SCOOP, 0.150f, 0.930f, 0.022f)

        // ------------------------------------------------ bottom
        if (stage(7)) buildBottom()

        // ------------------------------------------------ shooter gate
        if (stage(8))
        // Gate seals the lane exit diagonally: rising balls open it, balls
        // rolling back down the arch flank bounce off it and are deflected
        // left over the rail top into the N-E-X lanes.
        shooterGate = OneWayGate(
            physics, Ids.GATE_SHOOTER,
            0.455f, 0.933f,
            0.500f, 0.883f,
            dirX = 0.740f, dirY = 0.670f,
            zoneR = 0.030f,
        )
        }
    }

    /** Two channel walls from a centre polyline, on the ramp plane. */
    private fun buildTube(path: FloatArray, halfW: Float) {
        val n = path.size / 2
        val left = FloatArray(n * 2)
        val right = FloatArray(n * 2)
        for (i in 0 until n) {
            val prev = maxOf(i - 1, 0)
            val next = minOf(i + 1, n - 1)
            val dx = path[next * 2] - path[prev * 2]
            val dy = path[next * 2 + 1] - path[prev * 2 + 1]
            val l = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            val nx = -dy / l
            val ny = dx / l
            left[i * 2] = path[i * 2] + nx * halfW
            left[i * 2 + 1] = path[i * 2 + 1] + ny * halfW
            right[i * 2] = path[i * 2] - nx * halfW
            right[i * 2 + 1] = path[i * 2 + 1] - ny * halfW
        }
        val tubeBody = physics.staticBody(0f, 0f)
        physics.wallFixture(tubeBody, left, restitution = 0.25f, friction = 0.02f, category = Cat.RAMP, mask = Cat.BALL_RAMP)
        physics.wallFixture(tubeBody, right, restitution = 0.25f, friction = 0.02f, category = Cat.RAMP, mask = Cat.BALL_RAMP)
        wallRuns.add(left)
        wallRuns.add(right)
    }

    private fun buildBottom() {
        if (buildStage == 70) return // walls only, no elements
        // The lower playfield is symmetric about the centre of the area
        // between the LEFT wall (x=0) and the SHOOTER rail (x=0.468).
        val mirror = TableTuning.SHOOTER_X_INNER
        val rx = { v: Float -> mirror - v }

        // Outer funnels from the side walls down to the drain.
        wall(
            floatArrayOf(
                0f, 0.345f,
                0.030f, 0.300f,
                0.049f, 0.252f,
                0.058f, 0.205f,
                0.062f, 0.150f,
                0.075f, 0.095f,
                0.093f, 0.060f,
                0.118f, 0.048f,
            ),
        )
        wall(
            floatArrayOf(
                rx(0f), 0.345f,
                rx(0.030f), 0.300f,
                rx(0.049f), 0.252f,
                rx(0.058f), 0.205f,
                rx(0.062f), 0.150f,
                rx(0.075f), 0.095f,
                rx(0.093f), 0.060f,
                rx(0.118f), 0.048f,
            ),
        )

        // Outlane/inlane dividers with rounded tops.
        wall(floatArrayOf(0.088f, 0.170f, 0.088f, 0.295f))
        post(0.088f, 0.303f, 0.008f)
        wall(floatArrayOf(rx(0.088f), 0.170f, rx(0.088f), 0.295f))
        post(rx(0.088f), 0.303f, 0.008f)

        // Inlane guides ending above the flipper bases.
        wall(
            floatArrayOf(
                0.116f, 0.300f,
                0.116f, 0.190f,
                0.1225f, 0.163f,
                0.1310f, 0.152f,
                0.1380f, 0.1485f,
            ),
        )
        post(0.116f, 0.3075f, 0.0075f)
        wall(
            floatArrayOf(
                rx(0.116f), 0.300f,
                rx(0.116f), 0.190f,
                rx(0.1225f), 0.163f,
                rx(0.1310f), 0.152f,
                rx(0.1380f), 0.1485f,
            ),
        )
        post(rx(0.116f), 0.3075f, 0.0075f)

        // Slingshots: rubber face from the inner-bottom vertex up to the
        // outer-top vertex, kicking towards the centre.
        if (buildStage == 71) return
        slings.add(
            Slingshot(
                physics, Ids.SLING_L, left = true,
                verts = floatArrayOf(
                    0.124f, 0.200f, // outer-bottom
                    0.190f, 0.200f, // inner-bottom
                    0.124f, 0.288f, // outer-top
                ),
                ax = 0.190f, ay = 0.200f, bx = 0.124f, by = 0.288f,
                kickX = 0.815f, kickY = 0.584f,
            ),
        )
        slings.add(
            Slingshot(
                physics, Ids.SLING_R, left = false,
                verts = floatArrayOf(
                    rx(0.124f), 0.200f,
                    rx(0.190f), 0.200f,
                    rx(0.124f), 0.288f,
                ),
                ax = rx(0.190f), ay = 0.200f, bx = rx(0.124f), by = 0.288f,
                kickX = -0.815f, kickY = 0.584f,
            ),
        )

        // Flippers.
        if (buildStage == 72) return
        flipperL = Flipper(physics, 0.1425f, TableTuning.FLIPPER_PIVOT_Y, left = true)
        flipperR = Flipper(physics, rx(0.1425f), TableTuning.FLIPPER_PIVOT_Y, left = false)

        // Lane rollovers.
        rollovers[Ids.INLANE_L] = Rollover(physics, Ids.INLANE_L, 0.102f, 0.215f, 0.026f, 0.02f)
        rollovers[Ids.INLANE_R] = Rollover(physics, Ids.INLANE_R, rx(0.102f), 0.215f, 0.026f, 0.02f)
        rollovers[Ids.OUTLANE_L] = Rollover(physics, Ids.OUTLANE_L, 0.073f, 0.205f, 0.020f, 0.02f)
        rollovers[Ids.OUTLANE_R] = Rollover(physics, Ids.OUTLANE_R, rx(0.073f), 0.205f, 0.020f, 0.02f)
    }

    /** Ball start position on the plunger. */
    /** Reset transient element state (new game): drop targets up, gates home. */
    fun resetDynamicState() {
        dropBank.cancelReset()
        dropBank.reset()
        shooterGate.reset()
        scoop.reset()
        for (b in bumpers) b.reset()
        for (su in standups.values) su.reset()
        spinner.angle = 0f
    }

    fun plungerBallPos(out: Vec2): Vec2 {
        out.set(
            (TableTuning.SHOOTER_X_INNER + TableTuning.TABLE_W) * 0.5f,
            TableTuning.PLUNGER_Y,
        )
        return out
    }
}
