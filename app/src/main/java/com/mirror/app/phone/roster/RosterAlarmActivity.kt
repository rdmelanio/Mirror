package com.mirror.app.phone.roster

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.mirror.app.core.*
import java.time.*

class RosterAlarmActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopped = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { finish() } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!RosterStore.phone(this)) { finish(); return }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else { @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        val plan = intent.getStringExtra("key")?.let { RosterAlarms.find(this, it) } ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK); setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val caution = action("MASTER\nCAUTION") { acknowledge() }.apply { textSize = 40f; setTextColor(0xFFFFB000.toInt()); setBackgroundColor(Color.BLACK) }
        root.addView(caution, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(label(plan.alarm.label, 32f).apply { setTextColor(0xFFFFB000.toInt()) })
        val detail = label("", 22f).apply { setTextColor(Color.WHITE) }; root.addView(detail)
        root.addView(action("Snooze 10 min") { RosterAlarms.snooze(this, plan); acknowledge() })
        val update = object : Runnable { override fun run() {
            caution.alpha = if (SystemClock.elapsedRealtime() / 700 % 2 == 0L) 1f else 0.4f
            val minutes = maxOf(0, Duration.between(Instant.now(), plan.duty.reportInstant).toMinutes())
            val leg = plan.duty.legs.firstOrNull(); detail.text = "Report ${plan.duty.reportLocal?.toLocalTime()} ${leg?.depApt ?: "MNL"} · ${leg?.let { "5J${it.flightNo} → ${it.arrApt}" } ?: plan.duty.code} · T-${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
            handler.postDelayed(this, 500)
        } }; handler.post(update)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopped, IntentFilter("com.mirror.app.ROSTER_SOUND_STOPPED"), RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(stopped, IntentFilter("com.mirror.app.ROSTER_SOUND_STOPPED")) }
        setContentView(root)
    }
    private fun acknowledge() { startService(Intent(this, RosterSoundService::class.java).setAction(RosterSoundService.STOP)); finish() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); runCatching { unregisterReceiver(stopped) }; super.onDestroy() }
}
