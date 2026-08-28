package com.superheroghost.neonpinball.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Lightweight HUD overlay drawn with standard Android views on top of the GL
 * surface: score, ball number, message banner. Kept deliberately minimal so
 * it never obscures the playfield.
 *
 * On targetSdk 35+ Android enforces edge-to-edge: the content draws under the
 * status/gesture bars and into the display's rounded corners. The HUD insets
 * itself in [onApplyWindowInsets] so the corner-anchored labels (the "BALL n"
 * readout in particular) are never clipped by the screen edge.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context) : FrameLayout(context) {

    private val scoreView: TextView
    private val ballView: TextView
    private val messageView: TextView
    private val highView: TextView
    private val meterView: MeterView
    private val launchButton: TextView

    private var messageRunnable: Runnable? = null
    private var attachedInput: InputState? = null

    /** Base gutter, in px; system insets are added on top of it. */
    private val basePad =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()

    private lateinit var launchRowParams: LayoutParams

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        var cornerBottomLeft = 0
        var cornerBottomRight = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
            // Rounded-corner data arrived in S (31), one release after the
            // Type-based inset getters.
            if (Build.VERSION.SDK_INT >= 31) {
                cornerBottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                cornerBottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            }
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
            cornerBottomLeft = 0
        }

        setPadding(basePad + left, basePad + top, basePad + right, basePad + bottom)

        // The ball readout sits in the rounded corner: push it right far enough
        // that, at its height above the screen bottom, the corner arc cannot
        // cut into the first glyph. (Horizontal intrusion of a radius-R arc at
        // height y is at most R - y for y < R.)
        val labelBottomEdge = bottom + basePad
        val cornerClearance = (cornerBottomLeft - labelBottomEdge).coerceAtLeast(0)
        ballView.setPadding(basePad + cornerClearance, basePad, basePad, basePad)

        // Same clearance for the launch cluster on the right corner.
        if (::launchRowParams.isInitialized) {
            val rowClearance = (cornerBottomRight - labelBottomEdge).coerceAtLeast(0)
            launchRowParams.rightMargin = rowClearance
        }

        return super.onApplyWindowInsets(insets)
    }

    init {
        val pad = basePad

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

        // Launch cluster, bottom-right: vertical power meter + LAUNCH button.
        // Hold the button to charge; release to fire the plunger.
        val launchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meterView = MeterView(context)
        val meterParams = LinearLayout.LayoutParams(dp(14f), dp(110f))
        meterParams.rightMargin = dp(10f)
        launchRow.addView(meterView, meterParams)

        launchButton = tv("LAUNCH", 15f, COLOR_BRIGHT, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        launchButton.gravity = Gravity.CENTER
        launchButton.setBackgroundColor(0x3322E7FF)
        launchButton.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        launchRow.addView(
            launchButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        launchButton.setOnTouchListener { _, ev ->
            val inp = attachedInput
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inp?.plungerPull = 0f
                    inp?.plungerHeld = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    inp?.plungerHeld = false
                }
            }
            true
        }

        launchRowParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        addView(launchRow, launchRowParams)
    }

    /** Wire the launch button to the shared input state. */
    fun attachInput(input: InputState) {
        attachedInput = input
    }

    /** Power-meter level 0..1; safe to call from the GL thread. */
    fun setPlungerPower(power: Float) {
        meterView.power = power
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

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

/** Vertical launch-power meter; fill rises with the held charge. */
private class MeterView(context: Context) : View(context) {
    var power = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                postInvalidate()
            }
        }

    private val bgPaint = Paint().apply { color = 0x55101A33 }
    private val fillPaint = Paint()
    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF22E7FF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = w * 0.5f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        if (power > 0.01f) {
            fillPaint.color = colorFor(power)
            val fh = h * power
            canvas.drawRoundRect(0f, h - fh, w, h, r, r, fillPaint)
        }
        canvas.drawRoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, r, r, framePaint)
    }

    /** Cyan at low power, gold at full power. */
    private fun colorFor(p: Float): Int {
        val r = 0.13f + (1.00f - 0.13f) * p
        val g = 0.90f + (0.71f - 0.90f) * p
        val b = 1.00f + (0.33f - 1.00f) * p
        return (0xFF shl 24) or
            ((r * 255).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255).toInt().coerceIn(0, 255) shl 8) or
            (b * 255).toInt().coerceIn(0, 255)
    }
}
