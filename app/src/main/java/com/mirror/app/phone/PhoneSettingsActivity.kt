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
        "pairing" to "Pairing & security", "tv_launch" to "TV launch", "setup" to "Android setup", "diagnostics" to "Diagnostics", "about" to "About Mirror")
    private var selected: String? = null
    private var wide = false
    private var resumed = false
    private lateinit var navigation: ScrollView
    private lateinit var details: ScrollView
    private lateinit var separator: View
    private var titleChanged: (String) -> Unit = {}
    private val buttons = mutableMapOf<String, View>()
    private var clockPanel: ClockSettingsPanel? = null
    private var tvPanel: TvLaunchPanel? = null
    private var controls: PhoneControlsPanel? = null
    private var alarmPanel: DepartureSettingsPanel? = null
    private var alarmContent: LinearLayout? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        PhoneTheme.install(this); super.onCreate(savedInstanceState); volumeControlStream = AudioManager.STREAM_ALARM
        wide = resources.configuration.screenWidthDp >= 600
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; PhoneTheme.page(this) }
        val top = PhoneTheme.topBar(this, "Settings") { onBackPressedDispatcher.onBackPressed() }
        titleChanged = top.title; root.addView(top.view)
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val descriptions = mapOf("style" to "Clock appearance and layout", "roster_link" to "eCrew duties and imports", "alarms" to "Report-time countdowns", "calendar" to "Shared roster source", "weather" to "Weather on the clock", "info" to "Clock information lines", "display" to "Dimming and burn-in protection", "camera" to "Camera and streaming controls", "pairing" to "Paired TVs and access", "tv_launch" to "Launch apps on your TV", "setup" to "Android permissions and setup", "diagnostics" to "Capture log and developer options", "about" to "Version and font licenses")
        val menuCard = if (PhoneTheme.classic(this)) menu else PhoneTheme.card(this).also { menu.addView(it) }
        categories.forEach { (id, label) ->
            val button = if (PhoneTheme.classic(this)) action(label) { show(id) }.apply { textSize = if (wide) 14f else 17f; minHeight = dp(48) }
                else PhoneTheme.row(this, label, descriptions[id].orEmpty()) { show(id) }
            buttons[id] = button; menuCard.addView(button)
        }
        navigation = ScrollView(this).apply { addView(menu) }
        details = ScrollView(this).apply { isFillViewport = true }
        separator = View(this).apply { setBackgroundColor(PhoneTheme.divider(this)) }
        if (wide) {
            body.addView(navigation, LinearLayout.LayoutParams(dp(248), -1))
            body.addView(separator, LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(dp(12), 0, dp(12), 0) })
            body.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            body.addView(navigation, LinearLayout.LayoutParams(-1, -1))
            body.addView(separator, LinearLayout.LayoutParams(-1, dp(1))); separator.visibility = View.GONE
            body.addView(details, LinearLayout.LayoutParams(-1, -1)); details.visibility = View.GONE
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); PhoneUi.style(root)
        setContentView(root); insetContent(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!wide && selected != null) {
                    stopPage(); selected = null; navigation.visibility = View.VISIBLE; details.visibility = View.GONE; titleChanged("Settings")
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
            "roster_link" -> content.addView(action(if (com.mirror.app.BuildConfig.ROSTER_ENABLED) "Open Roster Link" else "Install mirror-phone.apk for Roster Link") { com.mirror.app.phone.roster.RosterEntry.open(this) })
            "diagnostics" -> MenuDiagnosticsPanel.build(this, content)
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
        PhoneTheme.groupSettings(content); details.addView(content); PhoneUi.style(content)
        buttons.forEach { (key, button) -> PhoneTheme.selectedRow(button, key == id) }
        titleChanged(categories[id].orEmpty())
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

