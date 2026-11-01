package com.dalmyo.museear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The panel's confirmation sounds (panel_voice.py tone()): sine tones with a short fade, 48 kHz. */
object Beeper {
    private const val RATE = 48000

    val LISTEN = tone(listOf(880, 1320))
    val HEARD = tone(listOf(1320), 0.06, 0.25)
    val OK = tone(listOf(1760), 0.08)
    val FAIL = tone(listOf(330, 220), 0.18)

    private fun tone(freqs: List<Int>, dur: Double = 0.12, vol: Double = 0.35): ShortArray {
        val n = (RATE * dur).toInt()
        val out = ShortArray(n * freqs.size)
        freqs.forEachIndexed { k, f ->
            for (i in 0 until n) {
                val env = min(1.0, min(i / 400.0, (n - i) / 400.0))
                out[k * n + i] = (32767 * vol * env * sin(2 * PI * f * i / RATE)).toInt().toShort()
            }
        }
        return out
    }

    /** Until then the phone's own beep may still be in the mic (the fail beep scored 0.39 on hey_mee_na in a test). */
    @Volatile var echoUntil = 0L
        private set

    /** Plays without blocking the caller (the mic loop must keep reading). */
    fun play(snd: ShortArray) = thread(name = "beep") {
        echoUntil = System.currentTimeMillis() + snd.size * 1000L / RATE + 600
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(snd.size * 2)
            .build()
        try {
            track.write(snd, 0, snd.size)
            track.play()
            Thread.sleep(snd.size * 1000L / RATE + 100)
        } catch (_: Exception) {
        } finally {
            track.release()
        }
    }
}
