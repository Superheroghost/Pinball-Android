package com.superheroghost.neonpinball.game

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Keeps HUD/menu content clear of the status bar, navigation bar and display
 * cutout.
 *
 * From Android 15 (targetSdk 35+) the platform draws every app edge-to-edge
 * behind translucent system bars whether the app opts in or not. Views parked
 * against a screen edge therefore end up underneath those bars and get clipped,
 * so edge-anchored HUD text needs explicit padding from the window insets.
 */
fun View.applySystemBarPadding(extraDp: Float = 0f) {
    val extra = (extraDp * resources.displayMetrics.density + 0.5f).toInt()

    setOnApplyWindowInsetsListener { view, insets ->
        val bars = systemBarInsets(insets)
        view.setPadding(
            bars.left + extra,
            bars.top + extra,
            bars.right + extra,
            bars.bottom + extra,
        )
        // Not consumed: children may still want to read the same insets.
        insets
    }

    // The first dispatch may already have happened (or not yet, if the view is
    // still detached); ask for one either way so padding is applied immediately.
    if (isAttachedToWindow) {
        requestApplyInsets()
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.requestApplyInsets()
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}

private class BarInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Suppress("DEPRECATION")
private fun systemBarInsets(insets: WindowInsets): BarInsets {
    if (Build.VERSION.SDK_INT >= 30) {
        val type = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val bars = insets.getInsets(type)
        return BarInsets(bars.left, bars.top, bars.right, bars.bottom)
    }
    return BarInsets(
        insets.systemWindowInsetLeft,
        insets.systemWindowInsetTop,
        insets.systemWindowInsetRight,
        insets.systemWindowInsetBottom,
    )
}
