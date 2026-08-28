package android.os

object Build {
    object VERSION {
        // Mutable so harness mains can simulate different API levels.
        @JvmField
        var SDK_INT: Int = 36
    }
}

open class Bundle

open class VibrationEffect {
    companion object {
        const val DEFAULT_AMPLITUDE = -1

        fun createOneShot(millis: Long, amplitude: Int): VibrationEffect = VibrationEffect()
    }
}

open class Vibrator {
    open fun hasVibrator(): Boolean = false
    open fun vibrate(effect: VibrationEffect) {}

    @Deprecated("Deprecated in Java")
    open fun vibrate(milliseconds: Long) {}
}

interface VibratorManager {
    val defaultVibrator: Vibrator?
}
