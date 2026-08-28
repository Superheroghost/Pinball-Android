package com.superheroghost.neonpinball

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.superheroghost.neonpinball.game.HudView

/**
 * Title screen: PLAY, HOW TO PLAY, HIGH SCORES, SETTINGS. Standard Android
 * views per the design rules (menus are not gameplay rendering).
 */
class MainActivity : Activity() {

    private lateinit var settings: SettingsStore

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        settings = SettingsStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF060913.toInt())
        }
        val pad = (resources.displayMetrics.density * 24f).toInt()

        val title = TextView(this).apply {
            text = "NEON\nNEXUS"
            textSize = 44f
            setTextColor(0xFFEFF4FF.toInt())
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(24f, 0f, 0f, 0xFF22E7FF.toInt())
            setPadding(pad, pad, pad, pad)
        }
        val subtitle = TextView(this).apply {
            text = "P I N B A L L"
            textSize = 16f
            setTextColor(0xFF22E7FF.toInt())
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, pad * 2)
        }

        val best = settings.highScores()[0]
        val bestView = TextView(this).apply {
            text = if (best > 0) "BEST  ${HudView.formatScore(best)}" else ""
            textSize = 14f
            setTextColor(0xFF8B96B8.toInt())
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, pad)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(menuButton("PLAY") { startGame() })
        root.addView(menuButton("HOW TO PLAY") { showPanel(howToText()) })
        root.addView(menuButton("HIGH SCORES") { showPanel(highScoresText()) })
        root.addView(menuButton("SETTINGS") { showSettings() })
        root.addView(bestView)

        setContentView(root)
        window.navigationBarColor = 0xFF060913.toInt()
        window.statusBarColor = 0xFF060913.toInt()
    }

    private fun startGame() {
        settings.gamesPlayed = settings.gamesPlayed + 1
        startActivity(android.content.Intent(this, GameActivity::class.java))
    }

    private fun menuButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this, null, 0)
        b.text = label
        b.textSize = 18f
        setTextColorish(b)
        b.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        b.background = null
        b.setPadding(0, 22, 0, 22)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun setTextColorish(b: Button) {
        b.setTextColor(0xFFEFF4FF.toInt())
        b.setOnHoverListener { _, _ -> false }
        b.setShadowLayer(10f, 0f, 0f, 0x6622E7FF)
    }

    private fun howToText(): String = """
        LEFT HALF OF SCREEN — left flipper
        RIGHT HALF — right flipper
        LAUNCH BUTTON — hold to charge power, release to launch

        N·E·X LANES complete to raise the bonus multiplier
        STANDUPS + LANES + DROP BANK light the LOCK
        SHOOT THE RAMP (top left tube) to lock balls
        LOCK 2 BALLS for MULTIBALL
        RAMP during multiball = JACKPOT (3 light SUPER)
        ORBIT when SUPER is lit = SUPER JACKPOT
        Clear all 6 objective arrows for OVERDRIVE (2x)
        Two drop-bank clears light EXTRA BALL at the scoop
    """.trimIndent()

    private fun highScoresText(): String {
        val scores = settings.highScores()
        val sb = StringBuilder("HIGH SCORES\n\n")
        var any = false
        for (i in 0 until 5) {
            if (scores[i] > 0) {
                sb.append("${i + 1}.  ${HudView.formatScore(scores[i])}\n")
                any = true
            }
        }
        if (!any) sb.append("No games yet — go play!")
        return sb.toString().trimEnd()
    }

    @SuppressLint("InflateParams")
    private fun showPanel(text: String) {
        val decor = window.decorView as? ViewGroup ?: return
        removePanel(decor)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE060913.toInt())
            tag = PANEL_TAG
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFC9D4F2.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
            setPadding(48, 48, 48, 48)
        }
        overlay.addView(tv)
        overlay.addView(closeButton { removePanel(decor) })
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    @SuppressLint("SetTextI18n")
    private fun showSettings() {
        val decor = window.decorView as? ViewGroup ?: return
        removePanel(decor)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE060913.toInt())
            tag = PANEL_TAG
        }

        fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
            val b = Button(this, null, 0)
            var on = initial
            fun render() {
                b.text = "$label: ${if (on) "ON" else "OFF"}"
                b.setTextColor(if (on) 0xFF22E7FF.toInt() else 0xFF8B96B8.toInt())
            }
            render()
            b.textSize = 16f
            b.background = null
            b.setPadding(0, 20, 0, 20)
            b.setOnClickListener {
                on = !on
                render()
                onChange(on)
            }
            return b
        }

        overlay.addView(TextView(this).apply {
            text = "SETTINGS"
            textSize = 22f
            setTextColor(0xFFEFF4FF.toInt())
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })
        overlay.addView(toggle("SOUND", settings.soundEnabled) { settings.soundEnabled = it })
        overlay.addView(toggle("HAPTICS", settings.hapticsEnabled) { settings.hapticsEnabled = it })
        overlay.addView(closeButton { removePanel(decor) })
        decor.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun closeButton(onClick: () -> Unit): Button {
        val b = Button(this, null, 0)
        b.text = "CLOSE"
        b.textSize = 15f
        b.setTextColor(0xFF8B96B8.toInt())
        b.background = null
        b.setPadding(0, 26, 0, 26)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun removePanel(decor: ViewGroup) {
        for (i in 0 until decor.childCount) {
            val child = decor.getChildAt(i)
            if (child.tag == PANEL_TAG) {
                decor.removeView(child)
                break
            }
        }
    }

    companion object {
        private const val PANEL_TAG = "menu-panel"
    }
}
