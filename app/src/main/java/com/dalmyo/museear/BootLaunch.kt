package com.dalmyo.museear

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings

/**
 * Opens one app once after each reboot and sends it away again. Made for SmartThings (갤럭시 업사이클링): its
 * 70 % charge limit only takes effect after the app has been opened since the boot.
 *
 * Android 10 blocks activity starts from the background unless the app may draw over other apps
 * ("다른 앱 위에 표시"), so that permission is needed.
 */
object BootLaunch {
    const val SMARTTHINGS = "com.samsung.android.smartthingshome"   // 갤럭시 업사이클링
    private const val OPEN_AFTER_MS = 15_000L   // let the phone settle after boot
    private const val HOME_AFTER_MS = 10_000L   // time for the app to start up before it's sent away
    private const val UNLOCK_DELAY_MS = 2_000L  // after the pattern, once the home screen is up

    private val main = Handler(Looper.getMainLooper())

    private var waiting: BroadcastReceiver? = null

    /**
     * Called when the service starts; does something only once per boot. Opened behind the pattern screen the app
     * doesn't set the limit (tested 2026-10-14: still charging at 97 % minutes later; opened after unlocking it
     * stopped charging at once), so while the phone is locked this waits for the first unlock.
     */
    fun onServiceStart(ctx: Context) {
        val app = ctx.applicationContext
        val pkg = Ear.settings(app).bootApp
        if (pkg.isEmpty() || waiting != null) return
        val boot = SystemSettings.Global.getInt(app.contentResolver, SystemSettings.Global.BOOT_COUNT, -1)
        val prefs = Settings.prefs(app)
        if (boot == prefs.getInt("boot_app_done", -2)) return
        val go = {
            prefs.edit().putInt("boot_app_done", boot).apply()
            run(app, Ear.settings(app).bootApp)
        }
        if (!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            main.postDelayed(go, OPEN_AFTER_MS)
            return
        }
        Ear.log("부팅 후 실행: 잠금 해제를 기다리는 중")
        // USER_PRESENT can't be declared in the manifest since Android 8, so the always-running service listens.
        waiting = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                app.unregisterReceiver(this)
                waiting = null
                main.postDelayed(go, UNLOCK_DELAY_MS)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33)   // a system broadcast; the flag is only required on newer phones
            app.registerReceiver(waiting, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        else app.registerReceiver(waiting, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    /** Open [pkg], then go to the home screen. Also used by the screen's "지금 시험" button. */
    fun run(ctx: Context, pkg: String) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { Ear.log("부팅 후 실행: $pkg 앱이 없음"); return }
        if (!SystemSettings.canDrawOverlays(ctx)) { Ear.log("부팅 후 실행: '다른 앱 위에 표시' 권한이 없어 못 엶"); return }
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Ear.log("부팅 후 실행: $pkg 열었음")
        } catch (e: Exception) {
            Ear.log("부팅 후 실행 실패: ${e.javaClass.simpleName}"); return
        }
        main.postDelayed({
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Ear.log("부팅 후 실행: 홈 화면으로 치움")
        }, HOME_AFTER_MS)
    }
}
