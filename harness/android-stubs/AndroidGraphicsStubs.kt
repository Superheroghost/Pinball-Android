package android.graphics

class Typeface {
    companion object {
        const val NORMAL = 0
        const val BOLD = 1

        val DEFAULT: Typeface = Typeface()

        fun create(familyName: String?, style: Int): Typeface = Typeface()
    }
}
