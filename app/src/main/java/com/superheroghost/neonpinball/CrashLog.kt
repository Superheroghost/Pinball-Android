package com.superheroghost.neonpinball

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Last-resort crash reporter. Writes the full stack trace of any uncaught
 * exception (on any thread — the GL thread included) to the app's external
 * files directory before delegating to the previous handler, so device-only
 * crashes can be pulled off the emulator instead of guessed at:
 * /sdcard/Android/data/com.superheroghost.neonpinball/files/crash-*.txt
 */
object CrashLog {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = try {
            context.getExternalFilesDir(null) ?: context.filesDir
        } catch (_: Throwable) {
            context.filesDir
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = StringWriter()
                sw.append("time: ${Date()}\n")
                sw.append("thread: ${thread.name}\n")
                ex.printStackTrace(PrintWriter(sw))
                val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
                file.writeText(sw.toString())
                Log.e("NeonCrash", "uncaught on '${thread.name}'; log at ${file.absolutePath}\n$sw")
            } catch (_: Throwable) {
                // Never mask the original crash.
            }
            previous?.uncaughtException(thread, ex)
        }
    }
}
