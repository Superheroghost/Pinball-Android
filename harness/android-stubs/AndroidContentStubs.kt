package android.content

import android.content.res.Resources

open class Context {
    open val resources: Resources = Resources()
    open val applicationContext: Context get() = this
    open val cacheDir: java.io.File = java.io.File(".")
    open val filesDir: java.io.File = java.io.File(".")

    open fun getExternalFilesDir(type: String?): java.io.File = java.io.File(".")

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = SharedPreferences()

    open fun getSystemService(name: String): Any? = null

    companion object {
        const val MODE_PRIVATE = 0
        const val VIBRATOR_SERVICE = "vibrator"
        const val VIBRATOR_MANAGER_SERVICE = "vibrator_manager"
    }
}

class Intent {
    constructor()
    constructor(packageContext: Context, cls: Class<*>)
}

open class SharedPreferences {
    open fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    open fun getFloat(key: String, defValue: Float): Float = defValue
    open fun getInt(key: String, defValue: Int): Int = defValue
    open fun getLong(key: String, defValue: Long): Long = defValue

    open fun edit(): Editor = Editor()

    class Editor {
        fun putBoolean(key: String, value: Boolean): Editor = this
        fun putFloat(key: String, value: Float): Editor = this
        fun putInt(key: String, value: Int): Editor = this
        fun putLong(key: String, value: Long): Editor = this
        fun apply() {}
    }
}
