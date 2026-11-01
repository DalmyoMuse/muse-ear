package com.dalmyo.museear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * A blank, see-through screen that exists only to start listening after a reboot on Android 14+.
 * Android 14 refuses a microphone service started from the background (BootReceiver), but allows it while one of
 * the app's screens is on top. This one turns the screen on, shows over the swipe lock, starts EarService and
 * closes itself. Opening it from the background needs "다른 앱 위에 표시" (SYSTEM_ALERT_WINDOW).
 */
class WakeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    override fun onResume() {
        super.onResume()
        try {
            EarService.start(this)
            Ear.log("자동 시작: 화면을 잠깐 띄워 마이크 켬")
        } catch (e: Exception) {
            Ear.log("자동 시작 실패: ${e.javaClass.simpleName}")
        }
        main.postDelayed({ finish() }, CLOSE_AFTER_MS)
    }

    companion object {
        private const val CLOSE_AFTER_MS = 1_500L   // the service has gone foreground well before this
    }
}
