package com.superheroghost.neonpinball.game

import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.TableTuning

/**
 * Fixed-timestep game loop driven from the render thread. Keeps the sim
 * deterministic and provides an interpolation alpha for smooth rendering.
 */
class GameLoop(private val sim: GameSim) {
    private var accumulator = 0f
    private var running = false

    /** 0..1 progress into the next physics step, for render interpolation. */
    var interpolationAlpha = 1f
        private set

    var input: InputState? = null
    var onFixedStep: (() -> Unit)? = null

    fun start() {
        running = true
        accumulator = 0f
    }

    fun pause() {
        running = false
    }

    fun resume() {
        running = true
        accumulator = 0f
    }

    fun update(dt: Float) {
        if (!running) {
            interpolationAlpha = 1f
            return
        }
        accumulator += dt
        var steps = 0
        while (accumulator >= TableTuning.FIXED_DT && steps < TableTuning.MAX_SUBSTEPS) {
            applyInput()
            sim.update(TableTuning.FIXED_DT)
            onFixedStep?.invoke()
            accumulator -= TableTuning.FIXED_DT
            steps++
        }
        if (steps == TableTuning.MAX_SUBSTEPS) {
            accumulator = 0f
        }
        interpolationAlpha = (accumulator / TableTuning.FIXED_DT).coerceIn(0f, 1f)
    }

    private fun applyInput() {
        val input = input ?: return
        sim.table.flipperL.setPressed(input.leftFlipper)
        sim.table.flipperR.setPressed(input.rightFlipper)
        if (input.plungerHeld) {
            sim.setPlungerPull(input.plungerPull)
        } else {
            sim.setPlungerHeld(false)
        }
    }
}
