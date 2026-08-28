package android.view

import android.content.Context
import android.graphics.drawable.Drawable

object Gravity {
    const val TOP = 0x30
    const val BOTTOM = 0x50
    const val LEFT = 0x03
    const val RIGHT = 0x05
    const val CENTER_VERTICAL = 0x10
    const val CENTER_HORIZONTAL = 0x01
    const val CENTER = CENTER_VERTICAL or CENTER_HORIZONTAL
    const val START = 0x00800003
}

open class Window {
    var navigationBarColor: Int = 0
    var statusBarColor: Int = 0
    val decorView: View = View()
}

open class View {
    val context: Context

    constructor() {
        context = Context()
    }

    constructor(context: Context) {
        this.context = context
    }

    val resources: android.content.res.Resources get() = context.resources

    var width: Int = 0
    var height: Int = 0
    var alpha: Float = 1f
    var visibility: Int = VISIBLE
    var tag: Any? = null
    open var background: Drawable? = null

    var padLeft = 0
    var padTop = 0
    var padRight = 0
    var padBottom = 0

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padLeft = left
        padTop = top
        padRight = right
        padBottom = bottom
    }

    fun setBackgroundColor(color: Int) {}
    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(action: Runnable) {}

    open fun onTouchEvent(event: MotionEvent): Boolean = false
    open fun onApplyWindowInsets(insets: WindowInsets): WindowInsets = insets

    fun animate(): ViewPropertyAnimator = ViewPropertyAnimator()
    fun setOnClickListener(listener: OnClickListener?) {}
    fun setOnHoverListener(listener: OnHoverListener?) {}

    fun interface OnClickListener {
        fun onClick(v: View)
    }

    fun interface OnHoverListener {
        fun onHover(v: View, event: MotionEvent): Boolean
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}

class ViewPropertyAnimator {
    fun alpha(value: Float): ViewPropertyAnimator = this
    fun setDuration(durationMillis: Long): ViewPropertyAnimator = this
    fun start() {}
}

open class ViewGroup(context: Context) : View(context) {
    private val children = ArrayList<View>()

    open val childCount: Int get() = children.size
    open fun getChildAt(index: Int): View = children[index]
    open fun addView(child: View) {
        children.add(child)
    }

    open fun addView(child: View, params: LayoutParams) {
        children.add(child)
    }

    open fun removeView(view: View) {
        children.remove(view)
    }

    open class LayoutParams {
        var width: Int = 0
        var height: Int = 0

        constructor(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}

open class MotionEvent {
    val actionMasked: Int get() = 0
    val actionIndex: Int get() = 0

    fun getX(pointerIndex: Int): Float = 0f
    fun getY(pointerIndex: Int): Float = 0f
    fun getPointerId(pointerIndex: Int): Int = 0
    fun findPointerIndex(pointerId: Int): Int = 0

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}

open class WindowInsets {
    open fun getInsets(typeMask: Int): Insets = Insets()
    open fun getRoundedCorner(position: Int): RoundedCorner? = null

    @Deprecated("Use getInsets instead")
    open val systemWindowInsetLeft: Int get() = 0

    @Deprecated("Use getInsets instead")
    open val systemWindowInsetTop: Int get() = 0

    @Deprecated("Use getInsets instead")
    open val systemWindowInsetRight: Int get() = 0

    @Deprecated("Use getInsets instead")
    open val systemWindowInsetBottom: Int get() = 0

    object Type {
        fun systemBars(): Int = 1
        fun displayCutout(): Int = 2
    }

    companion object {
        const val ROUNDED_CORNER_TOP_LEFT = 0
        const val ROUNDED_CORNER_TOP_RIGHT = 1
        const val ROUNDED_CORNER_BOTTOM_RIGHT = 2
        const val ROUNDED_CORNER_BOTTOM_LEFT = 3
    }
}

open class Insets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

open class RoundedCorner(val radius: Int = 0)
