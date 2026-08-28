package android.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

open class TextView(context: Context) : View(context) {
    var text: CharSequence? = null
    var textSize: Float = 0f
    var typeface: Typeface? = null
    var letterSpacing: Float = 0f
    var gravity: Int = 0

    fun setTextSize(unit: Int, size: Float) {}
    fun setTextColor(color: Int) {}
    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {}
    fun setLineSpacing(add: Float, multiplier: Float) {}
}

open class Button(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : TextView(context)

open class FrameLayout(context: Context) : ViewGroup(context) {
    class LayoutParams : ViewGroup.LayoutParams {
        var gravity: Int = 0
        var topMargin: Int = 0
        var bottomMargin: Int = 0
        var leftMargin: Int = 0
        var rightMargin: Int = 0

        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, gravity: Int) : super(width, height) {
            this.gravity = gravity
        }

        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}

open class LinearLayout(context: Context) : ViewGroup(context) {
    var orientation: Int = HORIZONTAL
    var gravity: Int = Gravity.TOP

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}
