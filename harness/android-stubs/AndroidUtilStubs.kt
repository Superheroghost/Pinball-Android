package android.util

open class DisplayMetrics {
    var density: Float = 1f
}

object TypedValue {
    const val COMPLEX_UNIT_PX = 0
    const val COMPLEX_UNIT_DIP = 1
    const val COMPLEX_UNIT_SP = 2

    fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = when (unit) {
        COMPLEX_UNIT_DIP -> value * metrics.density
        COMPLEX_UNIT_SP -> value * metrics.density
        else -> value
    }
}

interface AttributeSet
