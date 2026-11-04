package com.dalmyo.museear

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * The spoken "말씀하세요~" prompt (0.5): a clip recorded once with the house gsay voice
 * (Chirp3-HD Despina), kept in assets and played while the recognizer warms up. It is both
 * the "I heard you" and the "speak now" signal, replacing the 2 s of silence before the beep.
 * Its window is claimed in Beeper.echoUntil like our own beeps.
 */
object Prompt {
    fun play(ctx: Context) {
        try {
            val afd = ctx.assets.openFd("prompt_malsseum.wav")
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnPreparedListener { p ->
                Beeper.claimEcho(p.duration.toLong())
                p.start()
                Ear.log("안내멘트 재생 (${p.duration}ms)")
            }
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            Ear.log("안내멘트 재생 실패: ${e.message}")
        }
    }
}
