package com.superheroghost.neonpinball.game

/**
 * Thread-safe input state written by the UI thread and consumed by the game
 * thread. Touch handling never touches the simulation directly.
 */
class InputState {
    @Volatile var leftFlipper = false
    @Volatile var rightFlipper = false

    /**
     * Plunger: [plungerHeld] is true while the LAUNCH button is pressed; the
     * game loop then charges [plungerPull] 0..1 over time and the sim fires
     * when the button is released.
     */
    @Volatile var plungerPull = 0f
    @Volatile var plungerHeld = false

    /** One-shot pause request from the UI. */
    @Volatile var pauseRequested = false

    fun onTouchZones(x: Float, y: Float): Zone {
        return if (x < zoneSplit) Zone.LEFT else Zone.RIGHT
    }

    /** Normalised (0..1) split between left and right flipper zones. */
    @Volatile var zoneSplit = 0.5f

    enum class Zone { LEFT, RIGHT }
}
