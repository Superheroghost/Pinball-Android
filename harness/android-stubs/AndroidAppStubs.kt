package android.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window

open class Activity : Context() {
    val window: Window = Window()

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onPause() {}
    open fun onResume() {}
    open fun onDestroy() {}
    open fun onBackPressed() {}

    open fun setContentView(view: View) {}
    fun runOnUiThread(action: Runnable) { action.run() }
    open fun finish() {}
    open fun startActivity(intent: Intent) {}
}
