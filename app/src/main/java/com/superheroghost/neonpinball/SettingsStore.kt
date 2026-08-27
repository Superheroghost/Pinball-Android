package com.superheroghost.neonpinball

import android.content.Context
import android.content.SharedPreferences

/** Persisted settings + high scores. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("neon_pinball", Context.MODE_PRIVATE)

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    var fxScale: Float
        get() = prefs.getFloat("fx", 1f)
        set(v) = prefs.edit().putFloat("fx", v).apply()

    var gamesPlayed: Int
        get() = prefs.getInt("games", 0)
        set(v) = prefs.edit().putInt("games", v).apply()

    fun highScores(): LongArray {
        val out = LongArray(5)
        for (i in 0 until 5) out[i] = prefs.getLong("hs$i", 0L)
        return out
    }

    /** Insert a score; returns the 0-based rank, or -1 if it did not place. */
    fun submitScore(score: Long): Int {
        val scores = highScores().toMutableList()
        var rank = -1
        for (i in scores.indices) {
            if (score > scores[i]) {
                scores.add(i, score)
                scores.removeAt(5)
                rank = i
                break
            }
        }
        for (i in 0 until 5) prefs.edit().putLong("hs$i", scores[i]).apply()
        return rank
    }
}
