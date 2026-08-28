package com.superheroghost.neonpinball

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.superheroghost.neonpinball.game.GameAudio
import com.superheroghost.neonpinball.game.GameController
import com.superheroghost.neonpinball.game.GameSession
import com.superheroghost.neonpinball.game.HudView
import com.superheroghost.neonpinball.game.InputState
import com.superheroghost.neonpinball.game.ParticleSystem
import com.superheroghost.neonpinball.game.PinballRenderer
import com.superheroghost.neonpinball.game.PinballSurfaceView
import com.superheroghost.neonpinball.game.applySystemBarPadding
import com.superheroghost.neonpinball.sim.GameSim
import com.superheroghost.neonpinball.sim.SimEvent

/**
 * Hosts the pinball table. The GL surface renders the game; lightweight
 * Android views provide the HUD and pause overlay.
 */
class GameActivity : android.app.Activity(), GameController.Feedback {

    private lateinit var input: InputState
    private lateinit var sim: GameSim
    private lateinit var session: GameSession
    private lateinit var surface: PinballSurfaceView
    private lateinit var renderer: PinballRenderer
    private lateinit var controller: GameController
    private lateinit var hud: HudView

    private var vibrator: Vibrator? = null
    private var audio: GameAudio? = null
    private lateinit var settings: SettingsStore
    private var hapticsEnabled = true
    private var soundEnabled = true

    private var paused = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        input = InputState()
        sim = GameSim()
        val particles = ParticleSystem()

        session = GameSession(sim, object : GameSession.Listener {
            override fun onScoreChanged(score: Long) {
                runOnUiThread { hud.setScore(score) }
            }

            override fun onBallChanged(ball: Int) {
                runOnUiThread { hud.setBall(ball) }
            }

            override fun onMessage(text: String, duration: Float) {
                runOnUiThread { hud.showMessage(text, duration) }
            }

            override fun onGameOver(finalScore: Long) {
                settings.submitScore(finalScore)
                runOnUiThread { hud.showMessage("GAME OVER  ·  ${'$'}{HudView.formatScore(finalScore)}", 4f) }
                surface.postDelayed({ showGameOver() }, 2600)
            }

            override fun onFxEvent(event: SimEvent) {}
        })

        renderer = PinballRenderer(sim, session, particles)
        renderer.gameLoop.input = input

        controller = GameController(sim, session, particles, renderer.camera, this)

        surface = PinballSurfaceView(this, input)
        surface.setRenderer(renderer)
        surface.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.preserveEGLContextOnPause = true

        renderer.onFrame = { dt ->
            if (!paused) {
                session.update(dt)
                controller.frameTick(dt)
            }
        }
        controller.attach(renderer)

        hud = HudView(this)
        hud.setHighScore(SettingsStore(this).highScores()[0])
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        settings = SettingsStore(this)
        hapticsEnabled = settings.hapticsEnabled
        soundEnabled = settings.soundEnabled
        setupFeedback()
        audio?.setEnabled(soundEnabled)
        renderer.fxScale = settings.fxScale

        window.navigationBarColor = 0xFF060913.toInt()
        window.statusBarColor = 0xFF060913.toInt()

        surface.postDelayed({ startNewGame() }, 500)
    }

    private fun showGameOver() {
        if (session.phase != GameSession.Phase.GAME_OVER) return
        val decor = window.decorView as? ViewGroup ?: return
        for (i in 0 until decor.childCount) {
            if (decor.getChildAt(i).tag == PAUSE_TAG) return
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE060913.toInt())
        }
        overlay.tag = PAUSE_TAG
        overlay.addView(textView("GAME OVER", 28f, 0xFFEFF4FF.toInt()))
        overlay.addView(textView(HudView.formatScore(session.rules.score), 20f, 0xFFFFB454.toInt()))
        overlay.addView(button("PLAY AGAIN") {
            hidePauseOverlay()
            startNewGame()
        })
        overlay.addView(button("MAIN MENU") {
            hidePauseOverlay()
            finish()
        })
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.applySystemBarPadding(8f)
    }

    fun startNewGame() {
        runOnUiThread {
            hud.showMessage("NEON NEXUS", 1.4f)
        }
        session.startGame()
    }

    // ------------------------------------------------------------ feedback

    private fun setupFeedback() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        soundPoolInit()
    }

    private fun soundPoolInit() {
        audio = GameAudio(this).also { it.prepare() }
    }

    override fun haptic(kind: Int) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        val ms = when (kind) {
            GameController.HAPTIC_FLIPPER -> 6
            GameController.HAPTIC_BUMPER -> 12
            GameController.HAPTIC_TARGET -> 10
            GameController.HAPTIC_SLING -> 10
            GameController.HAPTIC_DRAIN -> 32
            GameController.HAPTIC_BIG -> 36
            else -> 8
        }
        if (v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.toLong())
            }
        }
    }

    override fun sound(kind: Int, param: Float) {
        if (!soundEnabled) return
        audio?.play(kind, param)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        surface.releaseAll()
        surface.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        audio?.release()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        hidePauseOverlay()
        paused = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session.isPlaying() && !paused) {
            showPauseOverlay()
        } else {
            super.onBackPressed()
        }
    }

    private fun showPauseOverlay() {
        paused = true
        val decor = window.decorView as? ViewGroup ?: return
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xDD060913.toInt())
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        overlay.addView(textView("PAUSED", 24f, 0xFFEFF4FF.toInt()))
        overlay.addView(button("RESUME") { hidePauseOverlay() })
        overlay.addView(button("RESTART") {
            hidePauseOverlay()
            startNewGame()
        })
        overlay.addView(button("QUIT") { finish() })
        overlay.tag = PAUSE_TAG
        decor.addView(overlay, params)
        overlay.applySystemBarPadding(8f)
    }

    private fun hidePauseOverlay() {
        paused = false
        val decor = window.decorView as? ViewGroup ?: return
        for (i in 0 until decor.childCount) {
            val child = decor.getChildAt(i)
            if (child.tag == PAUSE_TAG) {
                decor.removeView(child)
                break
            }
        }
    }

    private fun textView(text: String, size: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = size
        tv.setTextColor(color)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, 24, 0, 24)
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this, null, 0)
        b.text = text
        b.setTextColor(0xFF22E7FF.toInt())
        b.textSize = 16f
        b.background = null
        b.setPadding(0, 18, 0, 18)
        b.setOnClickListener { onClick() }
        return b
    }

    companion object {
        private const val PAUSE_TAG = "pause-overlay"
    }
}
