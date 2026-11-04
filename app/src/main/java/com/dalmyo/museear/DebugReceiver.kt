package com.dalmyo.museear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * adb-only test hooks (the receiver needs DUMP, which only the shell has):
 *
 *   adb shell am broadcast -a com.dalmyo.museear.TEST_WAV -n com.dalmyo.museear/.DebugReceiver --es file x.wav
 *     scores a 16 kHz mono WAV from /sdcard/Android/data/com.dalmyo.museear/files/ with 1 s of silence on each side
 *     (the same padding as the reference run on the server) and logs every chunk's score to logcat tag MuseEar.
 *
 *   adb shell am broadcast -a com.dalmyo.museear.SET -n com.dalmyo.museear/.DebugReceiver --es relay http://127.0.0.1:8097 --ez on true
 *     changes the relay address, turns listening on/off (on), or presses "듣기" (--ez listen true);
 *     also --ez capture true (save ~3 s before each wake hit under files/wake/), --ez jarvis false, --ef jarvis_th 0.4, --ef silent_db 6, --ef meena_th 0.6,
 *     --ei hold 3 (chunks in a row over the threshold before a wake counts; 1 = at once),
 *     --ez google_stt true (the phone's recognizer instead of voice-relay /stt),
 *     --es recognizer gapp (name the Google app recognition service directly; anything else = default),
 *     --ez test_audio true (self-test with a phrase played by the phone: no playback gate, nothing sent to the Muse; not saved)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.dalmyo.museear.SET" -> {
                val s = Ear.settings(ctx)
                val on = if (intent.hasExtra("on")) intent.getBooleanExtra("on", false) else s.enabled
                Settings.save(ctx, s.copy(relay = intent.getStringExtra("relay") ?: s.relay, enabled = on,
                    bootApp = intent.getStringExtra("boot_app") ?: s.bootApp,
                    jarvis = if (intent.hasExtra("jarvis")) intent.getBooleanExtra("jarvis", true) else s.jarvis,
                    silentDb = if (intent.hasExtra("silent_db")) intent.getFloatExtra("silent_db", s.silentDb) else s.silentDb,
                    meenaTh = if (intent.hasExtra("meena_th")) intent.getFloatExtra("meena_th", s.meenaTh) else s.meenaTh,
                    jarvisTh = if (intent.hasExtra("jarvis_th")) intent.getFloatExtra("jarvis_th", s.jarvisTh) else s.jarvisTh,
                    capture = if (intent.hasExtra("capture")) intent.getBooleanExtra("capture", false) else s.capture,
                    hold = if (intent.hasExtra("hold")) intent.getIntExtra("hold", s.hold).coerceAtLeast(1) else s.hold,
                    googleStt = if (intent.hasExtra("google_stt")) intent.getBooleanExtra("google_stt", false) else s.googleStt,
                    recognizer = intent.getStringExtra("recognizer") ?: s.recognizer))
                if (intent.getBooleanExtra("boot_test", false)) BootLaunch.run(ctx.applicationContext, Ear.settings(ctx).bootApp)
                val n = Ear.settings(ctx)
                Ear.log("설정 변경(adb): ${n.relay}, ${if (on) "켬" else "끔"}, 문턱값 ${n.meenaTh}, 무음 기준 ${n.silentDb}dB, 자비스 ${if (n.jarvis) "켬 ${n.jarvisTh}" else "끔"}, 오작동 확인 ${if (n.capture) "켬" else "끔"}, 유지 ${n.hold}칸, 받아쓰기 ${if (n.googleStt) "구글" else "voice-relay"}, 인식기 ${if (n.recognizer == "gapp") "구글앱" else "기본값"}")
                if (intent.hasExtra("on")) { if (on) EarService.start(ctx) else EarService.stop(ctx) }
                if (intent.hasExtra("test_audio")) Ear.testAudio = intent.getBooleanExtra("test_audio", false)
                if (intent.getBooleanExtra("listen", false)) Ear.trigger.set(true)
            }
            "com.dalmyo.museear.TEST_WAV" -> {
                val name = intent.getStringExtra("file") ?: return
                val pending = goAsync()
                thread {
                    try { score(ctx, File(ctx.getExternalFilesDir(null), name)) }
                    catch (e: Exception) { Log.e("MuseEar", "TEST_WAV $name", e) }
                    finally { pending.finish() }
                }
            }
        }
    }

    private fun score(ctx: Context, f: File) {
        val pcm = readWav(f)
        val pad = ShortArray(WakeWord.RATE)
        val x = pad + pcm + pad
        WakeWord.fromAssets(ctx).use { ww ->
            val t = System.nanoTime()
            val scores = (0..x.size - WakeWord.CHUNK step WakeWord.CHUNK).map { ww.process(x.copyOfRange(it, it + WakeWord.CHUNK)) }
            val ms = (System.nanoTime() - t) / 1_000_000
            for (n in ww.names) {
                val s = scores.map { it.getValue(n) }
                val best = s.indices.maxBy { s[it] }
                Log.i("MuseEar", "TEST ${f.name} $n samples=${x.size} max=%.4f argmax=$best ${s.joinToString(" ") { "%.3f".format(it) }}".format(s[best]))
            }
            Log.i("MuseEar", "TEST ${f.name} ${scores.size} chunks in ${ms}ms")
        }
    }

    /** PCM samples of a 16-bit mono WAV (walks the chunks to find "data"). */
    private fun readWav(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        while (b.remaining() >= 8) {
            val id = ByteArray(4).also { b.get(it) }.decodeToString()
            val len = b.int
            if (id == "data") return ShortArray(minOf(len, b.remaining()) / 2) { b.short }
            b.position(b.position() + len + (len and 1))
        }
        error("no data chunk")
    }
}
