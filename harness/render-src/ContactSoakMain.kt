package com.superheroghost.neonpinball.harness

import com.superheroghost.neonpinball.game.GameController
import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.game.ParticleSystem
import com.superheroghost.neonpinball.game.PinballRenderer
import com.superheroghost.neonpinball.game.InputState
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Contact soak: drives the full app-side event chain (sim -> session ->
 * GameController FX -> particles/camera/popups -> renderer draw) through
 * real flipper, slingshot and bumper contacts. On device this chain also
 * fires haptics/audio; those are recorded here through the Feedback seam.
 * Any throwable or GL error fails the run.
 */
object ContactSoakMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sim = GameSim()
        val session = GameSession(sim, object : GameSession.Listener {})
        val particles = ParticleSystem()
        val renderer = PinballRenderer(sim, session, particles)

        var haptics = 0
        var sounds = 0
        val seen = HashMap<String, Int>()
        val feedback = object : GameController.Feedback {
            override fun haptic(kind: Int) { haptics++ }
            override fun sound(kind: Int, param: Float) {
                sounds++
                if (!param.isFinite()) throw IllegalStateException("non-finite sound param $param for kind $kind")
            }
        }

        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 412, 892)
        session.startGame()

        val input = InputState()
        renderer.gameLoop.input = input
        renderer.gameLoop.onFixedStep = { session.onBallDrained() }
        val controller = GameController(sim, session, particles, renderer.camera, feedback)
        controller.attach(renderer)
        renderer.onFrame = { dt ->
            session.update(dt)
            controller.frameTick(dt)
        }

        // Count event kinds; sanity-check every coordinate that flows to FX.
        sim.onEvent?.let { }
        val baseHandler = sim.onEvent
        sim.onEvent = { e ->
            seen[e.javaClass.simpleName] = (seen[e.javaClass.simpleName] ?: 0) + 1
            when (e) {
                is SimEvent.BumperHit -> require(e.x.isFinite() && e.y.isFinite() && e.power.isFinite()) { "bad BumperHit" }
                is SimEvent.SlingHit -> require(e.x.isFinite() && e.y.isFinite() && e.power.isFinite()) { "bad SlingHit" }
                is SimEvent.FlipperHit -> require(e.x.isFinite() && e.y.isFinite() && e.power.isFinite()) { "bad FlipperHit" }
                is SimEvent.BallImpact -> require(e.power.isFinite()) { "bad BallImpact" }
                else -> {}
            }
            baseHandler?.invoke(e)
        }

        fun frames(seconds: Float) {
            val t0 = System.nanoTime()
            while ((System.nanoTime() - t0) / 1e9 < seconds) {
                renderer.onDrawFrame(null)
            }
        }

        // 1) Flipper contact: drop a ball on the left flipper and mash the
        //    flipper for the whole window (the loop is wall-clock driven, so
        //    a narrow flip window can miss under CPU load).
        sim.debugSpawnBall(0.16f, 0.28f)
        val t0 = System.nanoTime()
        while ((System.nanoTime() - t0) / 1e9 < 2.5) {
            input.leftFlipper = (System.nanoTime() / 150_000_000L) % 2L == 0L
            renderer.onDrawFrame(null)
        }
        input.leftFlipper = false

        // 2) Sling contact: fire a ball at the left slingshot face centre
        //    (face runs (0.206,0.200) -> (0.140,0.288), centre ~(0.173,0.244)).
        //    A single shot can glance off the top vertex, so retry until the
        //    event actually fires.
        repeat(8) {
            if ((seen["SlingHit"] ?: 0) > 0) return@repeat
            // Nearly-vertical drop onto the face centre: robust even when the
            // wall-clock-driven loop takes coarse steps under CPU load.
            sim.debugSpawnBall(0.176f, 0.268f, -0.05f, -1.2f)
            frames(0.8f)
        }

        // 3) Bumper contact: drop a ball onto the top bumper (0.221,0.754).
        repeat(3) {
            if ((seen["BumperHit"] ?: 0) > 0) return@repeat
            sim.debugSpawnBall(0.221f, 0.86f)
            frames(1.5f)
        }

        println("== contact soak")
        println("   events: $seen")
        println("   haptics=$haptics sounds=$sounds particles=${particles.activeCount} glErrors=${android.opengl.GLES20.glErrors}")
        val ok = (seen["FlipperHit"] ?: 0) > 0 &&
            (seen["SlingHit"] ?: 0) > 0 &&
            (seen["BumperHit"] ?: 0) > 0 &&
            haptics > 0 &&
            sounds > 0 &&
            android.opengl.GLES20.glErrors == 0
        println(if (ok) "CONTACT SOAK PASS" else "CONTACT SOAK FAIL")
        if (!ok) kotlin.system.exitProcess(1)
    }
}
