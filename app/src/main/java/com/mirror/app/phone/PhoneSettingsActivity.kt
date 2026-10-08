package com.mirror.app.phone

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.mirror.app.core.*

/** Shared settings destination; categories occupy the left pane on wide displays. */
open class PhoneSettingsActivity : ComponentActivity() {
    private val categories = linkedMapOf("style" to "Style & layout", "roster_link" to "Roster Link", "alarms" to "Departure alarms", "calendar" to "Duty & calendar",
        "weather" to "Weather", "info" to "Clock information", "display" to "Display protection", "camera" to "Camera & stream",
        "pairing" to "Pairing & security", "tv_launch" to "TV launch", "setup" to "Android setup", "about" to "About Mirror")
    private var selected: String? = null
    private var wide = false
    private var resumed = false
    private lateinit var navigation: ScrollView
    private lateinit var details: ScrollView
    private lateinit var separator: View
    private lateinit var heading: TextView
    private val buttons = mutableMapOf<String, Button>()
    private var clockPanel: ClockSettingsPanel? = null
    private var tvPanel: TvLaunchPanel? = null
    private var controls: PhoneControlsPanel? = null
    private var alarmPanel: DepartureSettingsPanel? = null
    private var alarmContent: LinearLayout? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); volumeControlStream = AudioManager.STREAM_ALARM
        wide = resources.configuration.screenWidthDp >= 600
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = action("BACK") { onBackPressedDispatcher.onBackPressed() }
        top.addView(back, LinearLayout.LayoutParams(dp(90), -2))
        heading = label("MIRROR / SETTINGS", 20f); top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(top)
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        categories.forEach { (id, label) ->
            val button = action(label) { show(id) }.apply { textSize = if (wide) 14f else 17f; minHeight = dp(48) }
            buttons[id] = button; menu.addView(button)
        }
        navigation = ScrollView(this).apply { addView(menu) }
        details = ScrollView(this).apply { isFillViewport = true }
        separator = View(this).apply { setBackgroundColor(0xFF305540.toInt()) }
        if (wide) {
            body.addView(navigation, LinearLayout.LayoutParams(dp(176), -1))
            body.addView(separator, LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(dp(12), 0, dp(12), 0) })
            body.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            body.addView(navigation, LinearLayout.LayoutParams(-1, -1))
            body.addView(separator, LinearLayout.LayoutParams(-1, dp(1))); separator.visibility = View.GONE
            body.addView(details, LinearLayout.LayoutParams(-1, -1)); details.visibility = View.GONE
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); PhoneUi.style(root); heading.setTextColor(PhoneUi.GREEN)
        setContentView(root); insetContent(root, 10)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!wide && selected != null) {
                    stopPage(); selected = null; navigation.visibility = View.VISIBLE; details.visibility = View.GONE; heading.text = "MIRROR / SETTINGS"
                } else finish()
            }
        })
        val restore = savedInstanceState?.getString("section") ?: intent.getStringExtra("section")
        if (restore in categories) show(restore!!) else if (wide) show("style")
    }
    private fun show(id: String) {
        if (id !in categories) return
        stopPage(); clockPanel = null; controls = null; tvPanel = null; selected = id
        details.removeAllViews()
        val content = if (id == "alarms" && alarmContent != null) alarmContent!! else LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (id) {
            "roster_link" -> content.addView(action("Open Roster Link") { startActivity(Intent(this, com.mirror.app.phone.roster.RosterActivity::class.java)) })
            "tv_launch" -> tvPanel = TvLaunchPanel(this, content)
            "alarms" -> if (alarmPanel == null) { alarmContent = content; alarmPanel = DepartureSettingsPanel(this, content) }
            "camera", "pairing" -> controls = PhoneControlsPanel(this, content, id)
            else -> {
                clockPanel = ClockSettingsPanel(this, content, id)
                if (id == "setup") {
                    content.addView(action("Battery optimization settings") {
                        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    })
                    content.addView(label("Alarm volume, notification, precise-alarm and full-screen permissions are under Departure alarms."))
                }
                if (id == "about") content.addView(action("Change role") {
                    com.mirror.app.phone.roster.RosterWork.cancel(this); com.mirror.app.phone.roster.RosterAlarms.cancel(this)
                    stopService(Intent(this, CameraService::class.java)); LauncherActivity.changeRole(this)
                })
            }
        }
        details.addView(content); PhoneUi.style(content)
        buttons.forEach { (key, button) -> button.isSelected = key == id }
        heading.text = "SETTINGS / ${categories[id]}"
        if (!wide) { navigation.visibility = View.GONE; details.visibility = View.VISIBLE }
        if (resumed) startPage()
    }
    private fun startPage() {
        if (selected == null) return
        clockPanel?.start(); controls?.start(); tvPanel?.start()
        if (selected == "alarms") alarmPanel?.start()
    }
    private fun stopPage() { clockPanel?.stop(); controls?.stop(); tvPanel?.stop(); alarmPanel?.stop() }
    override fun onResume() { super.onResume(); resumed = true; startPage() }
    override fun onPause() { resumed = false; stopPage(); super.onPause() }
    override fun onDestroy() { stopPage(); alarmPanel?.dispose(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("section", selected); super.onSaveInstanceState(outState) }
}
