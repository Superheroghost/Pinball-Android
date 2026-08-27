package com.superheroghost.neonpinball.sim

/**
 * Central place for every tunable physics/gameplay constant.
 *
 * Units are SI (metres, seconds, kilograms). The table is modelled at real
 * pinball dimensions: a 20.25" x 42" playfield is 0.514m x 1.067m. Physics run
 * with +Y up: (0,0) is the bottom-left corner of the playfield, (W,H) the top
 * right. The renderer flips Y when drawing.
 *
 * Effective gravity is stronger than a physically-tilted table (6.5 degrees)
 * because that plays too floaty on a phone; the value below is the tuned
 * arcade value used by good digital pinball.
 */
object TableTuning {
    // ---------------------------------------------------------------- table
    const val TABLE_W = 0.514f
    const val TABLE_H = 1.067f
    const val ARCH_CY = 0.80f
    const val ARCH_R = 0.257f // outer arc = top boundary
    const val ORBIT_GUIDE_R = 0.198f

    // ------------------------------------------------------------- physics
    const val FIXED_DT = 1f / 240f
    const val MAX_SUBSTEPS = 10
    const val VELOCITY_ITERATIONS = 8
    const val POSITION_ITERATIONS = 8

    /** Tuned "arcade" gravity. A real 6.5 degree table would be ~1.1. */
    const val GRAVITY = 3.62f

    // ---------------------------------------------------------------- ball
    const val BALL_R = 0.0135f
    const val BALL_DENSITY = 7850f // steel
    const val BALL_FRICTION = 0.045f
    const val BALL_RESTITUTION = 0.06f
    const val BALL_DAMPING = 0.055f

    // ------------------------------------------------------------- flippers
    const val FLIPPER_LEN = 0.0705f
    const val FLIPPER_BASE_R = 0.0148f
    const val FLIPPER_TIP_R = 0.0088f
    const val FLIPPER_DENSITY = 88f
    const val FLIPPER_UP_SPEED = 70.0f // rad/s (real machines sweep ~30deg in 10ms)
    const val FLIPPER_DOWN_SPEED = 12.0f // rad/s
    const val FLIPPER_TORQUE = 45.0f // N*m motor torque
    const val FLIPPER_INERTIA = 0.0035f // kg*m^2 about the pivot (velocity source)
    const val FLIPPER_REST_ANGLE = 0.44f // rad below horizontal
    const val FLIPPER_UP_ANGLE = 0.50f // rad above horizontal
    const val FLIPPER_PIVOT_Y = 0.148f

    // -------------------------------------------------------------- bumpers
    const val BUMPER_R = 0.0335f
    const val BUMPER_KICK = 2.30f // m/s added on hit
    const val BUMPER_KEEP = 0.25f // fraction of incoming speed kept
    const val BUMPER_COOLDOWN = 0.045f

    // ------------------------------------------------------------ slingshots
    const val SLING_KICK = 2.45f
    const val SLING_KEEP = 0.2f
    const val SLING_COOLDOWN = 0.14f
    const val SLING_MIN_SPEED = 0.25f

    // --------------------------------------------------------------- targets
    const val STANDUP_COOLDOWN = 0.09f
    const val DROP_BANK_RESET_DELAY = 0.8f

    // --------------------------------------------------------------- plunger
    const val PLUNGER_Y = 0.085f
    const val PLUNGER_MAX_SPEED = 3.35f
    const val PLUNGER_MIN_SPEED = 0.9f

    // ----------------------------------------------------------- ball save
    const val BALL_SAVE_TIME = 8.0f
    const val MBALL_SAVE_TIME = 12.0f

    // ---------------------------------------------------------------- misc
    const val DRAIN_Y = 0.035f
    const val SPIN_REV_FACTOR = 3.2f // spinner revs per metre of travel

    /** Restitution for ordinary walls. */
    const val WALL_RESTITUTION = 0.33f
    const val POST_RESTITUTION = 0.62f
    const val TARGET_RESTITUTION = 0.38f

    // Shooter lane geometry.
    const val SHOOTER_X_INNER = 0.460f
    const val SHOOTER_LANE_W = 0.054f // = TABLE_W - SHOOTER_X_INNER
}
