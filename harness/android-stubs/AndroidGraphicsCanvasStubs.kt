package android.graphics

open class Canvas {
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {}
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {}
}

open class Paint {
    var color: Int = 0
    var strokeWidth: Float = 0f
    var style: Style = Style.FILL

    enum class Style { FILL, STROKE, FILL_AND_STROKE }
}
