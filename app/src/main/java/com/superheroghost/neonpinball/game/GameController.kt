package com.superheroghost.neonpinball.game

import com.superheroghost.neonpinball.gl.Camera2D
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.SimEvent
import com.superheroghost.neonpinball.sim.TableTuning

/**
 * Central glue: pumps sim events into the session (scoring/flow) and spawns
 * feedback (particles, camera shake, flashes, haptics/audio hooks).
 */
class GameController(
    val sim: GameSim,
    val session: GameSession,
    val particles: ParticleSystem,
    val camera: Camera2D,
    val fx: Feedback,
) {
    interface Feedback {
        fun onEventFx(event: SimEvent) {}
        fun haptic(kind: Int) {}
        fun sound(kind: Int, param: Float) {}
    }

    private var renderer: PinballRenderer? = null

    /** Per-frame work driven by the activity (session timing etc). */
    fun frameTick(dt: Float) {}

    fun attach(renderer: PinballRenderer) {
        this.renderer = renderer
        sim.onEvent = { e ->
            session.handleEvent(e)
            handleFx(e)
        }
    }

    private fun handleFx(e: SimEvent) {
        val r = renderer ?: return
        val fxScale = r.fxScale
        when (e) {
            is SimEvent.BumperHit -> {
                particles.burst(e.x, e.y, 14, 0.25f, 0.9f, 0.5f, 0.0032f, 0.13f, 0.90f, 1.0f)
                camera.shake(0.004f, 0.10f)
                r.popup(e.x, e.y, gold = false)
                fx.haptic(HAPTIC_BUMPER)
                fx.sound(SOUND_BUMPER, 1f)
            }
            is SimEvent.SlingHit -> {
                particles.burst(e.x, e.y, 10, 0.3f, 0.8f, 0.35f, 0.0030f, 1.0f, 0.24f, 0.75f)
                camera.shake(0.003f, 0.08f)
                r.popup(e.x, e.y, gold = false)
                fx.haptic(HAPTIC_SLING)
                fx.sound(SOUND_SLING, 1f)
            }
            is SimEvent.Rollover -> {
                r.markLane(e.lane)
                r.popup(e.x, e.y, gold = false)
                fx.sound(SOUND_ROLLOVER, 1f)
            }
            is SimEvent.StandupHit -> {
                particles.burst(e.x, e.y, 12, 0.2f, 0.7f, 0.4f, 0.0030f, 1.0f, 0.71f, 0.33f)
                r.popup(e.x, e.y, gold = false)
                fx.haptic(HAPTIC_TARGET)
                fx.sound(SOUND_TARGET, 1f)
            }
            is SimEvent.DropTargetDown -> {
                particles.burst(e.x, e.y, 12, 0.2f, 0.7f, 0.4f, 0.0030f, 1.0f, 0.24f, 0.75f)
                r.popup(e.x, e.y, gold = false)
                fx.haptic(HAPTIC_TARGET)
                fx.sound(SOUND_TARGET, 1f)
            }
            is SimEvent.DropBankComplete -> {
                r.flash = 0.5f
                camera.shake(0.008f, 0.25f)
                particles.burst(0.40f, 0.64f, 30, 0.3f, 1.1f, 0.7f, 0.0040f, 1.0f, 0.71f, 0.33f)
                fx.haptic(HAPTIC_BIG)
                fx.sound(SOUND_BANK, 1f)
            }
            is SimEvent.SpinnerSpins -> {
                fx.sound(SOUND_SPINNER, e.revs.coerceAtMost(10) / 10f)
            }
            is SimEvent.FlipperHit -> {
                if (e.power > 0.45f) {
                    particles.burst(e.x, e.y, 6, 0.2f, 0.6f, 0.25f, 0.0022f, 0.8f, 0.95f, 1.0f)
                }
                fx.haptic(HAPTIC_FLIPPER)
                fx.sound(SOUND_FLIPPER, e.power)
            }
            is SimEvent.BallDrained -> {
                fx.haptic(HAPTIC_DRAIN)
                fx.sound(SOUND_DRAIN, 1f)
                camera.shake(0.005f, 0.2f)
            }
            is SimEvent.BallLaunched -> {
                fx.sound(SOUND_LAUNCH, e.speed / TableTuning.PLUNGER_MAX_SPEED)
            }
            is SimEvent.GatePassed -> {
                fx.sound(SOUND_GATE, 1f)
            }
            is SimEvent.HoleCapture -> {
                particles.burst(0.150f, 0.930f, 20, 0.1f, 0.5f, 0.6f, 0.0035f, 0.48f, 0.36f, 1.0f)
                fx.sound(SOUND_SCOOP, 1f)
            }
            is SimEvent.HoleEject -> {
                particles.burst(0.1345f, 0.8955f, 16, 0.2f, 0.7f, 0.5f, 0.0030f, 0.48f, 0.36f, 1.0f)
                fx.haptic(HAPTIC_TARGET)
            }
            is SimEvent.BallImpact -> {
                fx.sound(SOUND_BALLWALL, e.power)
            }
            else -> {}
        }
        if (fxScale <= 0f) return
    }

    companion object {
        // Haptic kinds.
        const val HAPTIC_FLIPPER = 0
        const val HAPTIC_BUMPER = 1
        const val HAPTIC_TARGET = 2
        const val HAPTIC_SLING = 3
        const val HAPTIC_DRAIN = 4
        const val HAPTIC_BIG = 5

        // Sound kinds.
        const val SOUND_FLIPPER = 0
        const val SOUND_BUMPER = 1
        const val SOUND_TARGET = 2
        const val SOUND_SLING = 3
        const val SOUND_DRAIN = 4
        const val SOUND_LAUNCH = 5
        const val SOUND_GATE = 6
        const val SOUND_SPINNER = 7
        const val SOUND_ROLLOVER = 8
        const val SOUND_BANK = 9
        const val SOUND_SCOOP = 10
        const val SOUND_BALLWALL = 11
        const val SOUND_UI = 12
    }
}
