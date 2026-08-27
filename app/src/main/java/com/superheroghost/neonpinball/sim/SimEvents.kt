package com.superheroghost.neonpinball.sim

/**
 * Events emitted by the simulation layer. The rules engine consumes them to
 * drive scoring/progression; the presentation layer consumes side-channel
 * events (sounds/effects are produced from the same bus).
 *
 * The sim owns a single reusable event queue; consumers must drain it each
 * tick, so event objects may be pooled without allocation in steady state.
 */
sealed class SimEvent {
    /** A pop bumper fired. */
    class BumperHit(val bumper: Int, val x: Float, val y: Float, val power: Float) : SimEvent()

    /** A slingshot fired. */
    class SlingHit(val sling: Int, val x: Float, val y: Float, val power: Float) : SimEvent()

    /** A rollover lane was crossed (inlane/outlane/top lanes). */
    class Rollover(val lane: Int, val x: Float, val y: Float) : SimEvent()

    /** A stand-up target was hit. */
    class StandupHit(val target: Int, val x: Float, val y: Float) : SimEvent()

    /** A drop target went down. */
    class DropTargetDown(val bank: Int, val index: Int, val x: Float, val y: Float) : SimEvent()

    /** All targets in a drop bank are down; reset is imminent. */
    class DropBankComplete(val bank: Int) : SimEvent()

    /** Spinner rotations completed. */
    class SpinnerSpins(val spinner: Int, val revs: Int) : SimEvent()

    /** A one-way gate was passed in its legal direction. */
    class GatePassed(val gate: Int, val x: Float, val y: Float) : SimEvent()

    /** Ball entered the ramp mouth. */
    class RampEntered(val ball: Int) : SimEvent()

    /** Ball reached the top of the ramp / arrived at the scoop. */
    class RampArrived(val ball: Int) : SimEvent()

    /** A full orbit pass over the top arch sensor. */
    class OrbitPassed(val direction: Int) : SimEvent()

    /** Ball was swallowed by the scoop/hole. */
    class HoleCapture(val ball: Int, val hole: Int) : SimEvent()

    /** Ball left the shooter lane into the playfield. */
    class BallLaunched(val ball: Int, val speed: Float) : SimEvent()

    /** Ball crossed the drain line. */
    class BallDrained(val ball: Int) : SimEvent()

    /** Flipper struck the ball; power in 0..1. */
    class FlipperHit(val left: Boolean, val power: Float, val x: Float, val y: Float) : SimEvent()

    /** Ball hit a wall/target hard; power in 0..1. */
    class BallImpact(val ball: Int, val power: Float, val x: Float, val y: Float) : SimEvent()

    /** A tracked shot sensor was crossed (used for combos and layer switches). */
    class ShotLane(val id: Int, val ball: Int, val x: Float, val y: Float, val vy: Float) : SimEvent()

    /** Ball fell below the capture hole exit guide (ejected back to play). */
    class HoleEject(val ball: Int) : SimEvent()

    /** Nothing - used for padding the pooled queue. */
    object None : SimEvent()
}

/** Routes presentation commands (lights, sounds, popups) out of the game. */
interface EffectSink {
    fun onLightChanged(lightId: Int, state: Int, param: Float)
}

/** Simple growable, reusable event queue (no steady-state allocation). */
class SimEventQueue(initialCapacity: Int = 128) {
    private var items = arrayOfNulls<Any?>(initialCapacity)
    private var size = 0

    fun clear() {
        size = 0
    }

    fun add(event: SimEvent) {
        if (size == items.size) {
            items = items.copyOf(items.size * 2)
        }
        items[size++] = event
    }

    operator fun get(index: Int): SimEvent = items[index] as SimEvent

    val count: Int get() = size
}

/** Iterate events without boxing. */
inline fun SimEventQueue.forEachEvent(block: (SimEvent) -> Unit) {
    for (i in 0 until count) {
        block(this[i])
    }
}
