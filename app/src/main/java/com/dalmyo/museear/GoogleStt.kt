package com.dalmyo.museear

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speech to text with the phone's own recognizer (on the Note 9 the Google app, the same engine as Chrome's
 * Web Speech), as the other choice to voice-relay /stt. The recognizer opens the mic itself and finds the end of
 * speech itself, so EarService hands its mic over for the turn and opens it again afterwards.
 */
object GoogleStt {
    /** texts: the n-best list, best first; conf: the recognizer's confidences if it gave any; error: SpeechRecognizer.ERROR_*. */
    class Result(val texts: List<String>, val conf: FloatArray?, val error: Int?)

    /**
     * Blocks the calling thread (never the main one) until the recognizer answers or [timeoutMs] passes.
     * [abort] is asked every 100 ms; when it says yes, the recognizer is cancelled and the result is ABORTED.
     * [onListening] runs once (main thread) when the mic really hears: "ready" comes ~1–2 s before Google's
     * recognizer takes in sound, so a beep at "ready" (or before starting) made 달묘's first words get cut.
     * It waits for the mic level to start moving, at least [minMs] after ready and at most 4 s.
     * The beep also waits until [beepFloorMs] from the start: the milestone alone jittered by over a
     * second between sessions, so a fixed floor gives the speaker a steady rhythm — the milestone can
     * only push the beep later, never earlier (boss's call, field night 2026-11-03→04).
     * Google's own start/end sounds (notification stream) are muted meanwhile, so only our beep is heard.
     */
    fun listen(ctx: Context, timeoutMs: Long = 15_000, minMs: Long = 400, beepFloorMs: Long = 2_000,
               onListening: () -> Unit = {}, onPrompt: () -> Unit = {}, promptDelayMs: Long = 350,
               abort: () -> Boolean = { false }): Result {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var out = Result(emptyList(), null, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        var sr: SpeechRecognizer? = null
        val t0 = System.currentTimeMillis()
        ready = -1; begin = -1; end = -1; level = -1; beeped = -1
        var firstRms: Float? = null
        fun go() {
            if (beeped >= 0 || done.count == 0L) return
            beeped = System.currentTimeMillis() - t0
            onListening()
        }
        quiet(ctx, true)
        // The spoken prompt starts a beat after the recognizer and ends around the old 2 s beep
        // floor, so it fills the silence without moving the speaker's proven rhythm (0.5).
        main.postDelayed({ if (done.count > 0) onPrompt() }, promptDelayMs)
        // Some recognizers send a late onError after onResults; only the first answer counts.
        fun finish(r: Result) { if (done.count > 0) { out = r; done.countDown() } }
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { finish(Result(emptyList(), null, NOT_AVAILABLE)); return@post }
            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle) = finish(Result(
                        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList(),
                        b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES), null))
                    override fun onError(error: Int) = finish(Result(emptyList(), null, error))
                    override fun onReadyForSpeech(params: Bundle?) {
                        ready = System.currentTimeMillis() - t0
                        main.postDelayed({ go() }, 4000)
                    }
                    override fun onBeginningOfSpeech() { begin = System.currentTimeMillis() - t0 }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (ready < 0 || level >= 0) return
                        if (firstRms == null) { firstRms = rmsdB; return }
                        if (rmsdB == firstRms) return
                        level = System.currentTimeMillis() - t0
                        val wait = maxOf(minMs - (level - ready), beepFloorMs - level)
                        if (wait > 0) main.postDelayed({ go() }, wait) else go()
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { end = System.currentTimeMillis() - t0 }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Hints only; the Google app may keep its own end-of-speech timing.
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName))
            }
        }
        while (!done.await(100, TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() - t0 > timeoutMs) break
            if (abort()) { finish(Result(emptyList(), null, ABORTED)); break }
        }
        main.post { sr?.cancel(); sr?.destroy() }   // runs after the post above, so sr is set by then
        main.postDelayed({ quiet(ctx, false) }, 700)  // after Google's end sound
        return out
    }

    /** Mutes the notification stream (Google's earcons) while listening; the flag survives a crash. */
    fun quiet(ctx: Context, on: Boolean) {
        val p = ctx.getSharedPreferences("google_stt", Context.MODE_PRIVATE)
        if (on == p.getBoolean("muted_noti", false)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        // Already silent (these speaker phones sit in silent mode): leave it alone, and never unmute it later.
        if (on && am.isStreamMute(AudioManager.STREAM_NOTIFICATION)) return
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            p.edit().putBoolean("muted_noti", on).apply()
        }.onFailure { Ear.log("알림음 ${if (on) "끄기" else "켜기"} 실패: ${it.message}") }
    }

    fun why(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "알아들은 말 없음"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말이 없음"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크"
        SpeechRecognizer.ERROR_AUDIO -> "마이크"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 없음"
        SpeechRecognizer.ERROR_SERVER -> "서버"
        NOT_AVAILABLE -> "이 폰에 음성 인식기가 없음"
        ABORTED -> "취소 (폰이 말하는 중)"
        else -> "오류 $error"
    }

    /** Milestones of the last listen() in ms from its start (-1 = didn't happen), for the log. */
    @Volatile var ready = -1L; private set
    @Volatile var begin = -1L; private set
    @Volatile var end = -1L; private set
    @Volatile var level = -1L; private set
    @Volatile var beeped = -1L; private set

    private const val NOT_AVAILABLE = -1
    const val ABORTED = -2
}
