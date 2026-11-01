package com.dalmyo.museear

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Settings the screen edits and the service reads. Defaults are the panel's (panel_voice.py). */
data class Settings(
    val relay: String,
    val meenaTh: Float,     // MEENA_TH
    val jarvis: Boolean,    // the panel also answers to the stock "hey jarvis"
    val silentDb: Float,    // SILENT_DB: the wake word must come with sound this far above the room's noise
    val enabled: Boolean,   // start again after a reboot or an app update
    val bootApp: String,    // package to open once after each reboot and then send away ("" = off), see BootLaunch
    val jarvisTh: Float = JARVIS_TH,
    val capture: Boolean = false,   // "오작동 확인 모드": save ~3 s before each wake hit (Capture.kt)
    // A wake counts only after this many 80 ms chunks in a row over the threshold (1 = at once). Real calls held
    // 5–7 chunks in the 10-18…10-20 captures; false wakes (TV, music, "가즈아", "미용") held 1–2.
    val hold: Int = HOLD,
    val googleStt: Boolean = false,   // speech to text with the phone's recognizer (GoogleStt) instead of voice-relay /stt
) {
    fun threshold(name: String): Float? = when {
        name.startsWith("hi_clio") -> meenaTh   // "하이 클리오"; the setting keeps its old key meena_th
        name.startsWith("hey_jarvis") -> if (jarvis) jarvisTh else null
        else -> null
    }

    companion object {
        const val DEFAULT_RELAY = "http://192.168.1.2:8097"
        // The stock model is trained on English "hey jarvis"; a Korean "헤이 자비스" scored 0.30–0.44 a few metres
        // away (2026-10-18), so the default is lower than the panel's 0.5.
        const val JARVIS_TH = 0.4f
        const val HOLD = 3

        /**
         * Settings live in device-protected storage so they can be read after a reboot before the pattern is
         * first entered (direct boot). The first call moves the old credential-protected file over.
         */
        fun prefs(ctx: Context) = ctx.createDeviceProtectedStorageContext().let { dp ->
            if (!dp.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("relay") &&
                (ctx.getSystemService(android.os.UserManager::class.java)).isUserUnlocked) {
                dp.moveSharedPreferencesFrom(ctx, "settings")
            }
            dp.getSharedPreferences("settings", Context.MODE_PRIVATE)
        }

        fun load(ctx: Context): Settings {
            val p = prefs(ctx)
            return Settings(
                relay = p.getString("relay", DEFAULT_RELAY)!!,
                meenaTh = p.getFloat("meena_th", 0.35f),
                jarvis = p.getBoolean("jarvis", true),
                silentDb = p.getFloat("silent_db", 12f),
                enabled = p.getBoolean("enabled", false),
                bootApp = p.getString("boot_app", "")!!,
                jarvisTh = p.getFloat("jarvis_th", JARVIS_TH),
                capture = p.getBoolean("capture", false),
                hold = p.getInt("hold", HOLD),
                googleStt = p.getBoolean("google_stt", false),
            )
        }

        fun save(ctx: Context, s: Settings) {
            prefs(ctx).edit()
                .putString("relay", s.relay).putFloat("meena_th", s.meenaTh).putBoolean("jarvis", s.jarvis)
                .putFloat("silent_db", s.silentDb).putBoolean("enabled", s.enabled).putString("boot_app", s.bootApp)
                .putFloat("jarvis_th", s.jarvisTh).putBoolean("capture", s.capture).putInt("hold", s.hold)
                .putBoolean("google_stt", s.googleStt).apply()
            Ear.settings = s
        }
    }
}

/** What the service is doing, for the screen (same process). Speech itself is never kept here. */
object Ear {
    @Volatile var settings: Settings? = null
    @Volatile var running = false
    @Volatile var state = "꺼짐"
    @Volatile var source = ""
    @Volatile var level = -90.0
    @Volatile var floor = -55.0
    @Volatile var score = 0f        // highest wake score in the last chunk
    val trigger = AtomicBoolean()   // "듣기" button: listen without the wake word
    // adb self-test (SET --ez test_audio true, not saved): the test phrase plays through the phone itself, so the
    // playback gate is off, and what is heard is only logged, not sent to the Muse.
    @Volatile var testAudio = false

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun settings(ctx: Context): Settings = settings ?: Settings.load(ctx).also { settings = it }

    fun log(msg: String) {
        Log.i("MuseEar", msg)
        synchronized(lines) {
            lines.addLast("${clock.format(Date())} $msg")
            while (lines.size > 200) lines.removeFirst()
        }
    }

    fun recent(n: Int): List<String> = synchronized(lines) { lines.takeLast(n) }
}
