package com.aird.designsystem.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.AudioManager
import com.aird.theme.ThemeStore
import java.util.concurrent.atomic.AtomicReference

/** Optional send/receive tones gated by ThemePrefs.sounds. */
object SoundFx {
    private val pool = AtomicReference<SoundPool?>(null)

    fun playSend(context: Context) {
        if (!ThemeStore.prefs.value.sounds) return
        beep(ToneGenerator.TONE_PROP_ACK, 40)
    }

    fun playReceive(context: Context) {
        if (!ThemeStore.prefs.value.sounds) return
        beep(ToneGenerator.TONE_PROP_BEEP, 35)
    }

    fun playReact(context: Context) {
        if (!ThemeStore.prefs.value.sounds) return
        beep(ToneGenerator.TONE_PROP_PROMPT, 30)
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            gen.startTone(tone, ms)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    gen.release()
                } catch (_: Exception) {
                }
            }, (ms + 80).toLong())
        } catch (_: Exception) {
        }
    }

    @Suppress("unused")
    fun ensurePool(context: Context): SoundPool {
        pool.get()?.let { return it }
        val created = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        return if (pool.compareAndSet(null, created)) created else {
            created.release()
            pool.get()!!
        }
    }
}
