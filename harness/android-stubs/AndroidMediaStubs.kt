package android.media

class AudioAttributes {
    class Builder {
        fun setUsage(usage: Int): Builder = this
        fun setContentType(contentType: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }

    companion object {
        const val USAGE_GAME = 14
        const val CONTENT_TYPE_SONIFICATION = 4
    }
}

class SoundPool {
    fun interface OnLoadCompleteListener {
        fun onLoadComplete(soundPool: SoundPool, sampleId: Int, status: Int)
    }

    fun setOnLoadCompleteListener(listener: OnLoadCompleteListener) {}
    fun load(path: String, priority: Int): Int = 0
    fun play(soundID: Int, leftVolume: Float, rightVolume: Float, priority: Int, loop: Int, rate: Float): Int = 0
    fun release(): Boolean = true

    class Builder {
        fun setMaxStreams(maxStreams: Int): Builder = this
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun build(): SoundPool = SoundPool()
    }
}
