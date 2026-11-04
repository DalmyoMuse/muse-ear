package com.dalmyo.museear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Always-on listening, the panel's loop (panel_voice.py main()) on the phone:
 * wake word → "듣는 중" beep → record until the talking stops → voice-relay /stt → short beep → /ask → ok/fail beep.
 * (뮤즈 귀: /ask goes to the Muse via the Termux bridge instead; the Muse speaks its answer on this phone.)
 * This app itself only beeps. Audio stays in memory and is never saved.
 */
class EarService : Service() {

    @Volatile private var loop: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var busy = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        GoogleStt.quiet(this, false) // notification sound left muted by a crash mid-listen
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: SecurityException) {
            // Android 14+: a mic service started while none of the app's screens is on top. Don't crash-loop.
            Ear.log("마이크 서비스 거절됨 (화면 없이 시작): 앱을 한 번 열어 주세요")
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) {
            stopping = false
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuseEar:listen").apply { acquire() }
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MuseEar:relay").apply { acquire() }
            loop = thread(name = "ear") { runLoop() }
            BootLaunch.onServiceStart(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        loop?.join(2000)
        loop = null
        wakeLock?.release()
        wifiLock?.release()
        Ear.running = false
        Ear.state = "꺼짐"
        Ear.log("듣기 중지")
        super.onDestroy()
    }

    private fun runLoop() {
        val engine = try {
            WakeWord.fromAssets(this)
        } catch (e: Exception) {
            Ear.log("호출어 모델을 못 읽음: ${e.message}")
            stopSelf(); return
        }
        try {
            var first = true
            // One pass per mic session. With Google STT the mic is closed for each request and opened again after.
            while (!stopping) {
                val rec = openMic()
                if (rec == null) { Ear.log("마이크를 열 수 없음"); return }
                var handOver = false
                try {
                    rec.startRecording()
                    Ear.running = true
                    if (first) Ear.log("듣기 시작 (${Ear.source}, 호출어: " +
                        engine.names.mapNotNull { n -> Ear.settings(this).threshold(n)?.let { "$n ≥$it" } }.joinToString() + ")")
                    first = false
                    handOver = listen(engine, rec)
                } catch (e: Exception) {
                    Ear.log("듣기 오류: ${e.javaClass.simpleName} ${e.message ?: ""}")
                } finally {
                    rec.stop(); rec.release()
                }
                if (!handOver) break
                googleTurn()
                engine.reset()
            }
        } finally {
            engine.close()
            Ear.running = false
            if (!stopping) stopSelf()   // START_STICKY brings the service back
        }
    }

    /** Listens until the service stops (false), or until a wake with Google STT on, when the mic must be handed over (true). */
    private fun listen(engine: WakeWord, rec: AudioRecord): Boolean {
        val chunk = ShortArray(CHUNK)
        val ring = ArrayDeque<ShortArray>()        // the last ~4 s, for the silence gate
        var floor = -55.0
        var lastNear = 0L
        var ev: Event? = null
        var lastEv = 0L
        var recording = false
        val frames = ByteArrayOutputStream()
        var t0 = 0L; var lastVoice = 0L; var spoke = false; var voiceDb = VOICE_DB
        var procNs = 0L; var procN = 0
        val started = System.currentTimeMillis()
        // The Muse answers out loud on this phone (gsay / termux-tts-speak). While anything but our own beeps
        // plays, and briefly after, its sound must not wake us or end up in a recording (it once sent Muse's
        // own answer back to it). Our beeps are media too, so their window (Beeper.echoUntil) is left out.
        val am = getSystemService(AudioManager::class.java)
        var speakerUntil = 0L
        var lastCapture = 0L
        var held = 0; var heldName = ""; var heldBest = 0f   // chunks in a row over the threshold (Settings.hold)

        while (!stopping) {
            var got = 0
            while (got < CHUNK) {
                val n = rec.read(chunk, got, CHUNK - got)
                if (n <= 0) throw IllegalStateException("마이크 끊김 ($n)")
                got += n
            }
            val s = Ear.settings(this)
            val lv = dbfs(chunk)
            val now = System.currentTimeMillis()
            ring.addLast(chunk.copyOf()); if (ring.size > 50) ring.removeFirst()
            Ear.level = lv
            val manual = Ear.trigger.getAndSet(false)
            val playing = now >= Beeper.echoUntil && am.isMusicActive
            if (playing) speakerUntil = now + SPEAKER_TAIL_MS

            if (!recording) {
                // Follows the room's noise: drops fast, rises slowly, so a call doesn't lift the floor it's judged
                // against (the old even 0.98/0.02 rose ~4 dB during one "헤이 미나").
                floor = if (lv < floor) 0.9 * floor + 0.1 * lv else 0.995 * floor + 0.005 * lv
                Ear.floor = floor
                val tp = System.nanoTime()
                val scores = engine.process(chunk)
                procNs += System.nanoTime() - tp; procN++
                if (procN == 7500) {   // every 10 minutes
                    Ear.log("처리시간 평균 %.1fms / 80ms".format(procNs / 7500 / 1e6))
                    procNs = 0; procN = 0
                }
                Ear.score = scores.values.maxOrNull() ?: 0f
                var hit = scores.filter { (n, sc) -> s.threshold(n)?.let { sc >= it } == true }
                val raw = hit          // before the gates below, for the capture mode
                var why = ""
                if (hit.isNotEmpty() && now - started < WARMUP_MS) {
                    // The first moments after the mic opens gave a 0.57 hey_mee_na out of nothing.
                    Ear.log("호출어 무시(막 켜짐) ${hit.keys.first()} %.2f".format(hit.values.max()))
                    engine.reset()
                    hit = emptyMap(); why = "warmup"
                }
                if (hit.isNotEmpty() && now < Beeper.echoUntil) {
                    Ear.log("호출어 무시(폰 자신의 소리) ${hit.keys.first()} %.2f".format(hit.values.max()))
                    engine.reset()
                    hit = emptyMap(); why = "beep"
                }
                if (hit.isNotEmpty() && now < speakerUntil) {
                    Ear.log("호출어 무시(폰이 말하는 중) ${hit.keys.first()} %.2f".format(hit.values.max()))
                    engine.reset()
                    hit = emptyMap(); why = "speaker"
                }

                // Diagnostics like the panel: one line per sound with its size and best scores. No speech is kept.
                if (lv > floor + 10) {
                    val e = ev ?: Event(now, lv).also { ev = it }
                    e.peak = maxOf(e.peak, lv); e.last = now
                    scores.forEach { (n, v) -> e.scores[n] = maxOf(e.scores[n] ?: 0f, v) }
                } else ev?.let { e ->
                    if (now - e.last > 1200) {
                        if (now - lastEv > 2000) {
                            lastEv = now
                            Ear.log("소리 %.1f초 크기 +%.0fdB 최대점수 %s%s".format(
                                (e.last - e.start) / 1000.0, e.peak - floor,
                                e.scores.entries.joinToString(" ") { "${it.key.substringBefore("_v")} %.2f".format(it.value) },
                                if (busy) " [처리중이라 무시]" else ""))
                        }
                        ev = null
                    }
                }
                if (hit.isEmpty() && !busy) {
                    scores.maxByOrNull { it.value }?.let { (n, sc) ->
                        if (sc >= NEAR_TH && now - lastNear > 3000) {
                            lastNear = now
                            Ear.log("근접 $n %.2f (문턱값 ${s.threshold(n) ?: "꺼짐"})".format(sc))
                        }
                    }
                }
                var pk = 99.0 + floor
                if (hit.isNotEmpty() && !manual && !busy) {
                    // Silence gate: a real call is ~+30 dB over the room; a score spike in a quiet room was +5–6 dB.
                    pk = ring.takeLast(14).maxOf { dbfs(it) }
                    if (pk - floor < s.silentDb) {
                        val best = hit.maxBy { it.value }
                        Ear.log("호출어 무시(소리 없음) ${best.key} (%.2f), 직전 최대 +%.0fdB".format(best.value, pk - floor))
                        engine.reset()
                        hit = emptyMap(); why = "quiet"
                    }
                }
                // Hold: a real "하이 클리오" stays over the threshold for 5–7 chunks; TV, music and near-words
                // like "가즈아" flicked over it for 1–2. Wait until the score has held for s.hold chunks.
                if (hit.isNotEmpty() && !manual && !busy) {
                    val best = hit.maxBy { it.value }
                    if (held == 0) heldName = best.key
                    held++; heldBest = maxOf(heldBest, best.value)
                    if (held < s.hold) { hit = emptyMap(); why = "hold" }
                } else if (held > 0) {
                    if (why.isEmpty()) {
                        Ear.log("호출어 무시(짧음) $heldName (%.2f), ${held}칸만 넘음 (기준 ${s.hold}칸)".format(heldBest))
                        if (s.capture && now - lastCapture > 2000) {
                            lastCapture = now
                            Capture.save(this, ring.takeLast(CAPTURE_CHUNKS), heldName, heldBest, "short")
                        }
                    }
                    held = 0; heldBest = 0f
                }
                if (raw.isNotEmpty() && why != "hold" && s.capture && now - lastCapture > 2000) {
                    lastCapture = now
                    val best = raw.maxBy { it.value }
                    val status = when { hit.isNotEmpty() && !busy -> "hit"; busy -> "busy"; else -> why.ifEmpty { "dropped" } }
                    Capture.save(this, ring.takeLast(CAPTURE_CHUNKS), best.key, best.value, status)
                }
                if ((hit.isNotEmpty() || manual) && !busy) {
                    if (hit.isNotEmpty()) {
                        Ear.log("호출어 감지 $heldName (최대 %.2f, ${held}칸), 소음 %.0fdB, 직전 최대 +%.0fdB".format(heldBest, floor, pk - floor))
                    } else Ear.log("버튼으로 듣기 시작")
                    held = 0; heldBest = 0f
                    // Google: no beep yet; googleTurn beeps once the recognizer really hears (see GoogleStt.listen).
                    if (s.googleStt) { Ear.state = "듣는 중"; return true }
                    // A quiet (far) call means the request will be quiet too: judge speech against the call's own size.
                    voiceDb = if (hit.isNotEmpty()) (pk - floor - 6).coerceIn(MIN_VOICE_DB, VOICE_DB) else VOICE_DB
                    Beeper.play(Beeper.LISTEN)
                    recording = true; frames.reset(); t0 = now; lastVoice = now; spoke = false
                    Ear.state = "듣는 중"
                    ev = null
                }
            } else if (playing) {
                // The phone started talking (usually the Muse answering an earlier question): drop the recording.
                Ear.log("녹음 취소 (폰이 말하는 중)")
                recording = false; engine.reset()
                Ear.state = "대기"
            } else {
                frames.write(le(chunk))
                // The phone's own "듣는 중" beep reaches its mic at about +30 dB and would count as speech
                // (then recording stops 0.9 s later), so the first half second isn't judged.
                if (lv > floor + voiceDb && now - t0 > BEEP_MS) { lastVoice = now; spoke = true }
                val done = (spoke && now - lastVoice > END_SIL_MS) || now - t0 > MAX_MS
                if (!spoke && now - t0 > NO_SPEECH_MS) {
                    Ear.log("말이 없음 (말 기준 +%.0fdB)".format(voiceDb))
                    Beeper.play(Beeper.FAIL)
                    recording = false; engine.reset()
                    Ear.state = "대기"
                } else if (done) {
                    val pcm = frames.toByteArray()
                    recording = false; engine.reset(); busy = true
                    Ear.state = "처리 중"
                    if (s.capture) Capture.saveCommand(this, pcm)
                    thread(name = "send") {
                        try { send(pcm, s.relay) } finally { busy = false; Ear.state = "대기" }
                    }
                }
            }
            if (!recording && !busy && Ear.state != "대기") Ear.state = "대기"
        }
        return false
    }

    /**
     * Speech to text (voice-relay /stt) → (short "들었어" beep) → the Muse via the Termux bridge → ok/fail beep.
     * The Muse then answers out loud on this phone by itself (termux-tts-speak).
     */
    private fun send(pcm: ByteArray, relayUrl: String) {
        val relay = Relay(relayUrl)
        val r = relay.stt(pcm)
        if (!r.optBoolean("ok")) {
            Ear.log("받아쓰기 실패: ${r.optString("why")}")
            Beeper.play(Beeper.FAIL); return
        }
        val text = r.optString("text")
        if (text.isBlank()) {
            Ear.log("받아쓰기 결과 없음")
            Beeper.play(Beeper.FAIL); return
        }
        Beeper.play(Beeper.HEARD)
        Ear.log("들음: '$text'")
        deliver(text)
    }

    private fun deliver(text: String) {
        if (Ear.testAudio) { Ear.log("시험 중이라 뮤즈에 안 보냄: '$text'"); Beeper.play(Beeper.OK); return }
        val r = Relay(Ear.settings(this).relay).askMuse(text)
        Ear.log("뮤즈 전달: ${if (r.optBoolean("ok")) "성공" else "실패 ${r.optString("why")}"}")
        Beeper.play(if (r.optBoolean("ok")) Beeper.OK else Beeper.FAIL)
    }

    /**
     * One request with the phone's recognizer (Settings.googleStt), on the mic loop's thread while the mic is closed.
     * Retries are off (GOOGLE_RETRY_MAX = 0): the field test (2026-11-04) showed an instant re-listen
     * fights the speaker's own rhythm — speech started before the retry beep and lost its first word.
     * The Muse gets the best guess plus up to two other candidates, so it can pick by context
     * ("음악 틀어줘" heard while music is playing, with "음악 꺼줘" as a candidate).
     */
    private fun googleTurn() {
        busy = true
        var sent = false   // once the send thread runs, it clears busy itself
        try {
            // Let any earlier beep end first (echoUntil is its end + 600 ms), or the recognizer starts on it.
            val wait = Beeper.echoUntil - 400 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            // As in voice-relay mode: if the phone starts playing (usually the Muse answering an earlier question),
            // the recognizer would hear the Muse and send its words back to it. The playback gate is off only for
            // adb self-tests (SET --ez test_audio true), which play the test phrase through the phone itself.
            val am = getSystemService(AudioManager::class.java)
            var attempt = 0
            while (true) {
            attempt++
            val t = System.currentTimeMillis()
            val r = GoogleStt.listen(this, onListening = {}, onPrompt = { Prompt.play(this) }) {
                !Ear.testAudio && System.currentTimeMillis() >= Beeper.echoUntil && am.isMusicActive
            }
            val ms = System.currentTimeMillis() - t
            Ear.log("구글 시간: 준비 ${GoogleStt.ready} · 소리 ${GoogleStt.level} · 안내 ${GoogleStt.beeped} · " +
                "말 시작 ${GoogleStt.begin} · 말 끝 ${GoogleStt.end} · 결과 ${ms}ms")
            val retryable = r.error == null || r.error == SpeechRecognizer.ERROR_NO_MATCH ||
                r.error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if ((r.error != null || r.texts.none { it.isNotBlank() }) && retryable && attempt <= GOOGLE_RETRY_MAX) {
                Ear.log("구글 재청취 $attempt/$GOOGLE_RETRY_MAX")
                Thread.sleep(300)
                continue
            }
            r.error?.let { e ->
                Ear.log("구글 받아쓰기 실패: ${GoogleStt.why(e)} (${ms}ms)")
                if (e != GoogleStt.ABORTED) Beeper.play(Beeper.FAIL)
                return
            }
            val rawTexts = r.texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // Our own prompt can echo into the transcript as a leading "말씀하세요": cut it off.
            val promptEcho = Regex("^\\s*말\\s*씀\\s*하\\s*세\\s*요[~!.,\\s]*")
            val texts = rawTexts.map { it.replace(promptEcho, "").trim() }.filter { it.isNotEmpty() }.distinct()
            if (texts.size != rawTexts.size) Ear.log("안내멘트 에코 제거: $rawTexts")
            if (texts.isEmpty()) {
                Ear.log("구글 받아쓰기 결과 없음 (${ms}ms)")
                Beeper.play(Beeper.FAIL); return
            }
            Beeper.play(Beeper.HEARD)
            Ear.log("구글 들음 (${ms}ms): " + texts.mapIndexed { i, x ->
                r.conf?.getOrNull(i)?.takeIf { it >= 0 }?.let { "'$x' %.2f".format(it) } ?: "'$x'" }.joinToString(" / "))
            // "음악 꺼 줘" / "음악 꺼줘" differ only in spacing: not worth showing the Muse.
            val others = texts.drop(1).distinctBy { it.replace(" ", "") }
                .filter { it.replace(" ", "") != texts[0].replace(" ", "") }.take(2)
            val msg = if (others.isEmpty()) texts[0] else "${texts[0]} (받아쓰기 다른 후보: ${others.joinToString(" / ")})"
            Ear.state = "처리 중"
            thread(name = "send") {
                try { deliver(msg) } finally { busy = false; Ear.state = "대기" }
            }
            sent = true
            break
            }
        } catch (e: Exception) {
            Ear.log("구글 받아쓰기 오류: ${e.javaClass.simpleName}")
        } finally {
            if (!sent) { busy = false; Ear.state = "대기" }
        }
    }

    /** UNPROCESSED if the phone has it, else VOICE_RECOGNITION, with the phone's gain control / noise cleanup off. */
    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val am = getSystemService(AudioManager::class.java)
        val sources = buildList {
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for ((src, name) in sources) {
            val r = try {
                AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
            } catch (e: Exception) { null } ?: continue
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
            val id = r.audioSessionId
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(id)?.enabled = false
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(id)?.enabled = false
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(id)?.enabled = false
            Ear.source = name
            return r
        }
        return null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ear", "듣기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "ear")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("뮤즈 귀")
            .setContentText("호출어를 듣고 있어요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private class Event(val start: Long, var peak: Double) {
        var last = start
        val scores = LinkedHashMap<String, Float>()
    }

    companion object {
        const val ACTION_STOP = "com.dalmyo.museear.STOP"
        private const val RATE = WakeWord.RATE
        private const val CHUNK = WakeWord.CHUNK          // 80 ms
        private const val NEAR_TH = 0.25f                 // below the threshold but logged, for tuning
        private const val MAX_MS = 8_000L
        private const val END_SIL_MS = 900L
        private const val NO_SPEECH_MS = 4_000L
        private const val BEEP_MS = 500L
        private const val WARMUP_MS = 2_500L
        private const val SPEAKER_TAIL_MS = 800L   // after other playback stops, the room still rings briefly
        private const val CAPTURE_CHUNKS = 38       // 38 × 80 ms ≈ 3 s before a wake hit, for the capture mode
        private const val VOICE_DB = 12.0                 // speech while recording: this far above the room (panel's value)
        private const val MIN_VOICE_DB = 5.0              // ...lowered down to this for a quiet call
        private const val GOOGLE_RETRY_MAX = 0            // field test: instant re-listens clipped first words; off

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, EarService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, EarService::class.java).setAction(ACTION_STOP))

        fun dbfs(a: ShortArray): Double {
            var sum = 0.0
            for (v in a) sum += v.toDouble() * v
            val r = sqrt(sum / a.size).takeIf { it > 0 } ?: 1.0
            return 20 * log10(r / 32768)
        }

        fun le(a: ShortArray): ByteArray {
            val b = ByteArray(a.size * 2)
            for (i in a.indices) { b[2 * i] = a[i].toByte(); b[2 * i + 1] = (a[i].toInt() shr 8).toByte() }
            return b
        }
    }
}
