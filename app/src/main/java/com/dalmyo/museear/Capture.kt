package com.dalmyo.museear

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "오작동 확인 모드" (off unless the user turns it on): when a wake word scores over its threshold, the ~3 s of
 * sound before it is saved as a WAV, so a false trigger can be listened to later (and given to the trainer as a
 * negative). Only these short clips are kept, in the app's own files dir under wake/, at most MAX_FILES.
 *   name: 20261018_192530_hi_clio_v2_0.60_hit.wav   status: hit | quiet | speaker | beep | warmup | busy | short
 * In voice-relay mode the request after each wake is kept too: 20261020_201500_cmd.wav.
 */
object Capture {
    private const val MAX_FILES = 100
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "wake").apply { mkdirs() }

    fun save(ctx: Context, chunks: List<ShortArray>, model: String, score: Float, status: String) {
        val pcm = chunks.map { it.copyOf() }   // the ring keeps changing after we return
        write(ctx, "%s_%s_%.2f_%s.wav".format(stamp.format(Date()), model, score, status)) {
            java.io.ByteArrayOutputStream().apply { pcm.forEach { write(EarService.le(it)) } }.toByteArray()
        }
    }

    /** The request recorded after a wake (voice-relay mode), so a wrong transcript can be checked against the sound. */
    fun saveCommand(ctx: Context, pcm: ByteArray) = write(ctx, "%s_cmd.wav".format(stamp.format(Date()))) { pcm }

    private fun write(ctx: Context, name: String, pcm: () -> ByteArray) = thread(name = "capture") {
        try {
            File(dir(ctx), name).writeBytes(Relay.wav(pcm()))
            dir(ctx).listFiles()?.sortedBy { it.name }?.dropLast(MAX_FILES)?.forEach { it.delete() }
            Ear.log("오작동 확인용 저장: $name")
        } catch (e: Exception) {
            Ear.log("저장 실패: ${e.javaClass.simpleName}")
        }
    }
}
