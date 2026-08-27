package com.superheroghost.neonpinball.game

/**
 * Thread-safe input state written by the UI thread and consumed by the game
 * thread. Touch handling never touches the simulation directly.
 */
class InputState {
    @Volatile var leftFlipper = false
    @Volatile var rightFlipper = false

    /** Plunger pull 0..1; >=0 means the plunger is gripped. */
    @Volatile var plungerPull = 0f
    @Volatile var plungerHeld = false

    /** Normalised (0..1) split between left and right flipper zones. */
    @Volatile var zoneSplit = 0.5f

    /** Fraction of the right edge reserved for the plunger strip. */
    @Volatile var plungerStrip = 0.14f

    /** One-shot pause request from the UI. */
    @Volatile var pauseRequested = false

    fun onTouchZones(x: Float, y: Float): Zone {
        return if (x >= 1f - plungerStrip && y >= 0.45f) Zone.PLUNGER else if (x < zoneSplit) Zone.LEFT else Zone.RIGHT
    }

    enum class Zone { LEFT, RIGHT, PLUNGER }
}
