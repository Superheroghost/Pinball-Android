package com.superheroghost.neonpinball.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural sound synthesis: every pinball sound is generated as a short PCM
 * WAV at runtime (no asset downloads needed). Sounds are written to the cache
 * dir once, then loaded into a [SoundPool].
 */
object AudioSynth {
    const val SAMPLE_RATE = 22050

    data class Spec(
        val durationSec: Float,
        /** Partial frequencies with individual decay rates. */
        val tones: FloatArray = FloatArray(0),
        val toneDecays: FloatArray = FloatArray(0),
        val toneLevels: FloatArray = FloatArray(0),
        /** Broadband click/thump amount at the start. */
        val noiseLevel: Float = 0f,
        val noiseDecay: Float = 200f,
        val noiseLP: Float = 0.25f,
        /** Pitch glide factor (>1 rises, <1 falls). */
        val glide: Float = 1f,
        val attackSec: Float = 0.002f,
    )

    fun synth(spec: Spec): ByteArray {
        val n = (spec.durationSec * SAMPLE_RATE).toInt()
        val pcm = ShortArray(n)
        var rngState = 0x9E3779B9L
        fun noise(): Float {
            rngState = rngState * 6364136223846793005L + 1442695040888963407L
            return ((rngState ushr 40).toInt() / 8388608.0).toFloat() - 1f
        }

        var lp = 0f
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val frac = t / spec.durationSec
            var s = 0f

            // Tonal partials with exponential decay + pitch glide.
            for (p in spec.tones.indices) {
                val glideFreq = spec.tones[p] * (1f + (spec.glide - 1f) * frac)
                val env = exp(-t * spec.toneDecays[p])
                s += sin(2.0 * PI * glideFreq * t).toFloat() * env * spec.toneLevels[p]
            }

            // Noise transient (one-pole low-passed).
            if (spec.noiseLevel > 0f) {
                lp += (noise() - lp) * spec.noiseLP
                s += lp * spec.noiseLevel * exp(-t * spec.noiseDecay)
            }

            // Attack ramp to avoid clicks.
            val atk = if (t < spec.attackSec) t / spec.attackSec else 1f
            // Gentle release at the tail.
            val rel = min(1f, (spec.durationSec - t) / 0.01f)
            s *= atk * rel

            pcm[i] = (s * 32000f).coerceIn(-32767f, 32767f).toInt().toShort()
        }
        return wav(pcm)
    }

    /** Wrap mono 16-bit PCM in a WAV container. */
    fun wav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        fun str(s: String) { for (c in s) out.write(c.code) }
        str("RIFF"); le32(36 + dataSize); str("WAVE")
        str("fmt "); le32(16); le16(1); le16(1)
        le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
        str("data"); le32(dataSize)
        for (v in pcm) le16(v.toInt())
        return out.toByteArray()
    }

    // ------------------------------------------------------------ presets

    fun flipper() = Spec(
        durationSec = 0.06f,
        tones = floatArrayOf(160f, 90f),
        toneDecays = floatArrayOf(90f, 60f),
        toneLevels = floatArrayOf(0.25f, 0.45f),
        noiseLevel = 0.5f, noiseDecay = 320f, noiseLP = 0.5f,
    )

    fun bumper() = Spec(
        durationSec = 0.16f,
        tones = floatArrayOf(880f, 1320f, 1760f),
        toneDecays = floatArrayOf(34f, 40f, 55f),
        toneLevels = floatArrayOf(0.32f, 0.2f, 0.12f),
        noiseLevel = 0.22f, noiseDecay = 400f, noiseLP = 0.6f,
    )

    fun target() = Spec(
        durationSec = 0.10f,
        tones = floatArrayOf(520f, 780f),
        toneDecays = floatArrayOf(48f, 70f),
        toneLevels = floatArrayOf(0.4f, 0.18f),
        noiseLevel = 0.3f, noiseDecay = 300f, noiseLP = 0.45f,
    )

    fun sling() = Spec(
        durationSec = 0.08f,
        tones = floatArrayOf(300f, 640f),
        toneDecays = floatArrayOf(70f, 90f),
        toneLevels = floatArrayOf(0.35f, 0.22f),
        noiseLevel = 0.4f, noiseDecay = 350f, noiseLP = 0.5f,
    )

    fun drain() = Spec(
        durationSec = 0.55f,
        tones = floatArrayOf(400f, 205f),
        toneDecays = floatArrayOf(7f, 5f),
        toneLevels = floatArrayOf(0.3f, 0.34f),
        glide = 0.55f,
    )

    fun launch() = Spec(
        durationSec = 0.35f,
        tones = floatArrayOf(200f),
        toneDecays = floatArrayOf(6f),
        toneLevels = floatArrayOf(0.22f),
        noiseLevel = 0.4f, noiseDecay = 12f, noiseLP = 0.22f,
        glide = 2.4f,
    )

    fun gate() = Spec(
        durationSec = 0.05f,
        tones = floatArrayOf(1150f),
        toneDecays = floatArrayOf(150f),
        toneLevels = floatArrayOf(0.25f),
        noiseLevel = 0.3f, noiseDecay = 500f, noiseLP = 0.65f,
    )

    fun spinner() = Spec(
        durationSec = 0.04f,
        tones = floatArrayOf(1750f),
        toneDecays = floatArrayOf(140f),
        toneLevels = floatArrayOf(0.16f),
        noiseLevel = 0.15f, noiseDecay = 500f, noiseLP = 0.7f,
    )

    fun rollover() = Spec(
        durationSec = 0.14f,
        tones = floatArrayOf(1568f, 2093f),
        toneDecays = floatArrayOf(24f, 30f),
        toneLevels = floatArrayOf(0.2f, 0.14f),
    )

    fun fanfare() = Spec(
        durationSec = 0.7f,
        tones = floatArrayOf(523f, 659f, 784f, 1047f),
        toneDecays = floatArrayOf(5.5f, 5.5f, 6f, 4.5f),
        toneLevels = floatArrayOf(0.26f, 0.26f, 0.26f, 0.3f),
    )

    fun scoopGulp() = Spec(
        durationSec = 0.3f,
        tones = floatArrayOf(240f, 130f),
        toneDecays = floatArrayOf(16f, 11f),
        toneLevels = floatArrayOf(0.3f, 0.34f),
        glide = 0.5f,
        noiseLevel = 0.15f, noiseDecay = 40f, noiseLP = 0.3f,
    )

    fun thud() = Spec(
        durationSec = 0.08f,
        tones = floatArrayOf(120f),
        toneDecays = floatArrayOf(70f),
        toneLevels = floatArrayOf(0.4f),
        noiseLevel = 0.35f, noiseDecay = 260f, noiseLP = 0.3f,
    )

    fun uiBeep() = Spec(
        durationSec = 0.09f,
        tones = floatArrayOf(1200f),
        toneDecays = floatArrayOf(40f),
        toneLevels = floatArrayOf(0.2f),
    )
}

/**
 * Owns the [SoundPool], synthesizes all sounds on first use, plays them by
 * [GameController] sound kind. Volume scales with event intensity.
 */
class GameAudio(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private var prepared = false
    private val ids = IntArray(16) { 0 }
    private val loaded = BooleanArray(16) { false }
    private var enabled = true

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun prepare() {
        if (prepared) return
        prepared = true
        val specs = arrayOf(
            AudioSynth.flipper(), AudioSynth.bumper(), AudioSynth.target(), AudioSynth.sling(),
            AudioSynth.drain(), AudioSynth.launch(), AudioSynth.gate(), AudioSynth.spinner(),
            AudioSynth.rollover(), AudioSynth.fanfare(), AudioSynth.scoopGulp(), AudioSynth.thud(),
            AudioSynth.uiBeep(),
        )
        val dir = File(appContext.cacheDir, "sfx")
        dir.mkdirs()
        for (i in specs.indices) {
            val f = File(dir, "sfx$i.wav")
            if (!f.exists()) {
                FileOutputStream(f).use { it.write(AudioSynth.synth(specs[i])) }
            }
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                for (k in ids.indices) {
                    if (ids[k] == sampleId && status == 0) loaded[k] = true
                }
            }
            ids[i] = pool.load(f.path, 1)
        }
    }

    private val appContext = context.applicationContext

    fun play(kind: Int, intensity: Float) {
        if (!enabled) return
        val id = ids.getOrNull(kind) ?: return
        if (id == 0 || !loaded[kind]) return
        val vol = 0.5f + 0.5f * intensity.coerceIn(0f, 1f)
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
