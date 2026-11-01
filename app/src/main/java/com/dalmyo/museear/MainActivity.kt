package com.dalmyo.museear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Status, a level meter, on/off, a "듣기" button, the few settings, and the recent log. */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var levels: TextView
    private lateinit var toggle: Button
    private lateinit var battery: Button
    private lateinit var relay: EditText
    private lateinit var meenaTh: EditText
    private lateinit var silentDb: EditText
    private lateinit var jarvis: CheckBox
    private lateinit var googleStt: CheckBox
    private lateinit var bootApp: CheckBox
    private lateinit var overlayBtn: Button
    private lateinit var logView: TextView

    private val refresh = object : Runnable {
        override fun run() { update(); ui.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Ear.settings(this)

        status = TextView(this).apply { textSize = 20f }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 90 }
        levels = TextView(this).apply { textSize = 13f; alpha = 0.8f }
        toggle = button("") { toggle() }
        battery = button("배터리 최적화 제외하기") { askBatteryExemption() }
        relay = field(s.relay, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        meenaTh = field(s.meenaTh.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        silentDb = field(s.silentDb.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        jarvis = CheckBox(this).apply { text = "\"헤이 자비스\"에도 반응 (문턱값 ${s.jarvisTh})"; isChecked = s.jarvis }
        googleStt = CheckBox(this).apply {
            text = "받아쓰기를 구글 음성 인식으로 (끄면 voice-relay)"
            isChecked = s.googleStt
            setOnCheckedChangeListener { _, on ->
                Settings.save(this@MainActivity, Ear.settings(this@MainActivity).copy(googleStt = on))
                Ear.log("받아쓰기: ${if (on) "구글" else "voice-relay"}")
            }
        }
        bootApp = CheckBox(this).apply {
            text = "재부팅 후 업사이클링(SmartThings) 한 번 열었다 닫기 — 충전 제한 적용용"
            isChecked = s.bootApp.isNotEmpty()
            setOnCheckedChangeListener { _, on ->
                Settings.save(this@MainActivity, Ear.settings(this@MainActivity).copy(bootApp = if (on) BootLaunch.SMARTTHINGS else ""))
            }
        }
        overlayBtn = button("'다른 앱 위에 표시' 허용하기 (부팅 후 실행에 필요)") {
            startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(meter)
            addView(levels)
            addView(row(toggle, button("듣기") { Ear.trigger.set(true) }))
            addView(battery)
            addView(label("voice-relay 주소"))
            addView(relay)
            addView(row(labeled("하이 클리오 문턱값", meenaTh), labeled("무음 기준(dB)", silentDb)))
            addView(jarvis)
            addView(googleStt)
            addView(button("설정 저장") { saveSettings() })
            addView(bootApp)
            addView(overlayBtn)
            addView(button("업사이클링 열었다 닫기 지금 시험") { BootLaunch.run(applicationContext, BootLaunch.SMARTTHINGS) })
            addView(label("기록 (말 내용은 받아쓴 글자만, 소리는 저장 안 함)"))
            addView(logView)
        }
        // Android 10 (the Note 9) doesn't draw behind the system bars, so plain padding is enough.
        setContentView(ScrollView(this).apply { addView(content); setPadding(dp(16), dp(16), dp(16), dp(16)) })

        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun update() {
        status.text = when {
            !Ear.running -> "● 꺼짐"
            else -> "● ${Ear.state} · ${Ear.source}"
        }
        meter.progress = (Ear.level + 90).toInt().coerceIn(0, 90)
        levels.text = if (Ear.running)
            "소리 %.0fdB · 소음 %.0fdB · 호출어 점수 %.2f".format(Ear.level, Ear.floor, Ear.score) else ""
        toggle.text = if (Ear.running) "끄기" else "켜기"
        battery.visibility = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        overlayBtn.visibility = if (SystemSettings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        logView.text = Ear.recent(60).asReversed().joinToString("\n")
    }

    private fun toggle() {
        val on = !Ear.running
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        Settings.save(this, Ear.settings(this).copy(enabled = on))
        if (on) EarService.start(this) else EarService.stop(this)
    }

    private fun saveSettings() {
        val th = meenaTh.text.toString().toFloatOrNull()
        val db = silentDb.text.toString().toFloatOrNull()
        val url = relay.text.toString().trim()
        if (th == null || th !in 0.05f..0.99f || db == null || db !in 0f..60f || !url.startsWith("http")) {
            Toast.makeText(this, "값을 확인해 주세요 (문턱값 0.05~0.99, dB 0~60)", Toast.LENGTH_SHORT).show(); return
        }
        Settings.save(this, Ear.settings(this).copy(relay = url, meenaTh = th, silentDb = db, jarvis = jarvis.isChecked))
        Ear.log("설정 저장: 문턱값 $th, 무음 기준 ${db}dB, 자비스 ${if (jarvis.isChecked) "켬" else "끔"}")
        Toast.makeText(this, "저장했어요 (바로 적용)", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("BatteryLife") // a dedicated always-on listening phone
    private fun askBatteryExemption() {
        startActivity(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }
    private fun field(value: String, type: Int) = EditText(this).apply { setText(value); inputType = type; isSingleLine = true }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 13f; alpha = 0.7f; setPadding(0, dp(12), 0, 0) }
    private fun labeled(text: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; addView(label(text)); addView(v)
    }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
