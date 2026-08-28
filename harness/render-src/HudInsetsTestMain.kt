package com.superheroghost.neonpinball.harness

import android.content.Context
import android.os.Build
import android.view.Insets
import android.view.RoundedCorner
import android.view.WindowInsets
import android.widget.TextView
import com.superheroghost.neonpinball.game.HudView

/**
 * Headless check of the HUD's window-inset handling. Runs the real
 * [HudView.onApplyWindowInsets] against simulated Pixel-class insets
 * (status bar, gesture bar, rounded bottom-left corner) and asserts the
 * corner-anchored "BALL n" readout is pushed clear of the screen corner.
 */
object HudInsetsTestMain {

    private var failures = 0

    private class TestInsets(
        private val l: Int,
        private val t: Int,
        private val r: Int,
        private val b: Int,
        private val cornerRadius: Int,
    ) : WindowInsets() {
        override fun getInsets(typeMask: Int): Insets = Insets(l, t, r, b)

        override fun getRoundedCorner(position: Int): RoundedCorner? =
            if (cornerRadius <= 0) null else RoundedCorner(cornerRadius)

        @Deprecated("Use getInsets instead", ReplaceWith("getInsets(typeMask)"))
        override val systemWindowInsetLeft: Int get() = l

        @Deprecated("Use getInsets instead", ReplaceWith("getInsets(typeMask)"))
        override val systemWindowInsetTop: Int get() = t

        @Deprecated("Use getInsets instead", ReplaceWith("getInsets(typeMask)"))
        override val systemWindowInsetRight: Int get() = r

        @Deprecated("Use getInsets instead", ReplaceWith("getInsets(typeMask)"))
        override val systemWindowInsetBottom: Int get() = b
    }

    private fun check(name: String, ok: Boolean, detail: String) {
        println((if (ok) "  PASS  " else "  FAIL  ") + name.padEnd(40) + detail)
        if (!ok) failures++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Pixel 10 Pro-ish: 420 dpi -> density 2.625.
        val ctx = Context()
        ctx.resources.displayMetrics.density = 2.625f
        val base = (10 * 2.625f).toInt() // basePad

        // ---- modern edge-to-edge device: bars + rounded corner.
        Build.VERSION.SDK_INT = 36
        val statusBar = 128 // px
        val gestureBar = 63 // px
        val corner = 126 // px radius

        val hud = HudView(ctx)
        hud.onApplyWindowInsets(TestInsets(0, statusBar, 0, gestureBar, corner))

        val ball = hud.getChildAt(2) as TextView
        val expectClearance = (corner - (gestureBar + base)).coerceAtLeast(0)

        println("== hud inset test (SDK 36)")
        println("   container padding l/t/r/b = ${hud.padLeft}/${hud.padTop}/${hud.padRight}/${hud.padBottom}")
        println("   ball label padding l/t/r/b = ${ball.padLeft}/${ball.padTop}/${ball.padRight}/${ball.padBottom}")

        check("top padding clears status bar", hud.padTop == base + statusBar, "padTop=${hud.padTop}, want ${base + statusBar}")
        check("bottom padding clears gesture bar", hud.padBottom == base + gestureBar, "padBottom=${hud.padBottom}, want ${base + gestureBar}")
        check("ball label clears rounded corner", ball.padLeft >= base + expectClearance, "padLeft=${ball.padLeft}, want >= ${base + expectClearance}")
        check("clearance actually needed & applied", expectClearance > 0 && ball.padLeft > hud.padLeft, "clearance=$expectClearance")

        // ---- legacy device (API 29): deprecated inset path, no corner data.
        Build.VERSION.SDK_INT = 29
        val hudOld = HudView(ctx)
        hudOld.onApplyWindowInsets(TestInsets(0, statusBar, 0, gestureBar, corner))
        val ballOld = hudOld.getChildAt(2) as TextView

        println("== hud inset test (SDK 29)")
        println("   container padding l/t/r/b = ${hudOld.padLeft}/${hudOld.padTop}/${hudOld.padRight}/${hudOld.padBottom}")
        check("legacy insets applied (top)", hudOld.padTop == base + statusBar, "padTop=${hudOld.padTop}")
        check("legacy ball label keeps base gutter", ballOld.padLeft == base, "padLeft=${ballOld.padLeft}, want $base")

        println()
        if (failures == 0) {
            println("HUD INSET TEST PASS")
        } else {
            println("HUD INSET TEST: $failures FAILURE(S)")
            kotlin.system.exitProcess(1)
        }
    }
}
