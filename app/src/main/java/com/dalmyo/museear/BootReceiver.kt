package com.dalmyo.museear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as SystemSettings

/**
 * Starts listening again after a reboot or an app update, if it was on. (Android 10 allows a mic service from here.)
 * LOCKED_BOOT_COMPLETED comes before the pattern is first entered; BOOT_COMPLETED after it, when the service is
 * usually running already (starting it again is harmless).
 * Android 14+ forbids that from the background, so there it opens WakeActivity, which starts the service on screen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Ear.settings(ctx).enabled) return
        val why = intent.action?.substringAfterLast('.')
        if (Build.VERSION.SDK_INT < 34) {
            Ear.log("자동 시작 ($why)")
            EarService.start(ctx)
            return
        }
        if (Ear.running) return
        if (!SystemSettings.canDrawOverlays(ctx)) {
            Ear.log("자동 시작 못 함 ($why): '다른 앱 위에 표시' 권한이 없음. 앱을 열어 켜 주세요")
            return
        }
        Ear.log("자동 시작 ($why): 화면을 잠깐 띄움")
        ctx.startActivity(Intent(ctx, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}
