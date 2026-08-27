package com.superheroghost.neonpinball.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

/**
 * Lightweight HUD overlay drawn with standard Android views on top of the GL
 * surface: score, ball number, message banner. Kept deliberately minimal so
 * it never obscures the playfield.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context) : FrameLayout(context) {

    private val scoreView: TextView
    private val ballView: TextView
    private val messageView: TextView
    private val highView: TextView

    private var messageRunnable: Runnable? = null

    init {
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()

        scoreView = tv("0", 30f, COLOR_BRIGHT, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        scoreView.setPadding(pad, pad, pad, 0)
        val scoreParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        addView(scoreView, scoreParams)

        highView = tv("", 12f, COLOR_DIM, Typeface.DEFAULT)
        highView.setPadding(pad, 0, pad, 0)
        val highParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        highParams.topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 46f, resources.displayMetrics).toInt()
        addView(highView, highParams)

        ballView = tv("", 13f, COLOR_CYAN, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        ballView.setPadding(pad, pad, pad, pad)
        val ballParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        addView(ballView, ballParams)

        messageView = tv("", 20f, COLOR_GOLD, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        messageView.setPadding(pad, 0, pad, 0)
        val msgParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        msgParams.bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 90f, resources.displayMetrics).toInt()
        addView(messageView, msgParams)
    }

    private fun tv(text: String, sizeSp: Float, color: Int, face: Typeface): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setTextColor(color)
        tv.typeface = face
        tv.setShadowLayer(8f, 0f, 0f, color and 0x00FFFFFF or (0x66 shl 24))
        return tv
    }

    fun setScore(score: Long) {
        scoreView.text = formatScore(score)
    }

    fun setBall(ball: Int) {
        ballView.text = "BALL $ball"
    }

    fun setHighScore(score: Long) {
        highView.text = if (score > 0) "BEST ${formatScore(score)}" else ""
    }

    fun showMessage(text: String, durationSec: Float) {
        messageRunnable?.let { removeCallbacks(it) }
        messageView.text = text
        messageView.alpha = 1f
        messageView.visibility = VISIBLE
        val fade = Runnable {
            messageView.animate().alpha(0f).setDuration(400L).start()
        }
        messageRunnable = fade
        postDelayed(fade, (durationSec * 1000).toLong())
    }

    companion object {
        private const val COLOR_BRIGHT = 0xFFEFF4FF.toInt()
        private const val COLOR_DIM = 0xFF8B96B8.toInt()
        private const val COLOR_CYAN = 0xFF22E7FF.toInt()
        private const val COLOR_GOLD = 0xFFFFB454.toInt()

        fun formatScore(score: Long): String {
            if (score >= 1_000_000L) {
                return String.format(Locale.US, "%.2fM", score / 1_000_000.0)
            } else if (score >= 10_000L) {
                return String.format(Locale.US, "%dk", score / 1000)
            }
            return score.toString()
        }
    }
}
