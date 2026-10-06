package com.mirror.app.phone

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mirror.app.core.*

class DepartureSettingsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var caution: EditText
    private lateinit var warning: EditText
    private lateinit var duration: EditText
    private lateinit var excluded: EditText
    private var soundKind = DeparturePlan.Kind.CAUTION
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateStatus() }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStatus(); DepartureAlerts.configure(this) }
    private val soundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.fold(onSuccess = {
                val s = ClockSettings.load(this)
                (if (soundKind == DeparturePlan.Kind.CAUTION) s.copy(cautionSound = uri.toString()) else s.copy(warningSound = uri.toString())).save(this)
                Toast.makeText(this, "Audio clip saved · use Test to check it", Toast.LENGTH_LONG).show()
            }, onFailure = { Toast.makeText(this, "Could not keep access to that clip. Choose a local audio file.", Toast.LENGTH_LONG).show() })
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); volumeControlStream = AudioManager.STREAM_ALARM
        savedInstanceState?.getString("soundKind")?.let { soundKind = DeparturePlan.Kind.valueOf(it) }
        val s = ClockSettings.load(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("Leave for duty", 28f))
        content.addView(label("Two calendar departure alarms, always in Philippine time. Flights, AS and timed training are included; OFF, HS and HSA are skipped by default. Alarms work without leaving Clock mode open."))
        fun toggle(title: String, value: Boolean, change: (ClockSettings, Boolean) -> ClockSettings) {
            content.addView(CheckBox(this).apply {
                text = title; isChecked = value; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> change(ClockSettings.load(this@DepartureSettingsActivity), checked).save(this@DepartureSettingsActivity); updateStatus() }
            })
        }
        toggle("Enable departure alarms", s.departureEnabled) { settings, checked -> settings.copy(departureEnabled = checked) }
        toggle("Master caution · single chime and persistent amber", s.cautionEnabled) { settings, checked -> settings.copy(cautionEnabled = checked) }
        toggle("Master warning · repeating sound and flashing red", s.warningEnabled) { settings, checked -> settings.copy(warningEnabled = checked) }
        fun number(title: String, value: Int): EditText {
            content.addView(label(title))
            return EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText(value.toString()); contentDescription = title; content.addView(this) }
        }
        caution = number("Caution: minutes before reporting (1–1440)", s.cautionMinutes)
        warning = number("Warning: minutes before reporting (1–1440)", s.warningMinutes)
        duration = number("Warning sound: seconds (1–600)", s.warningSeconds)
        content.addView(label("Default: caution 60 minutes before, warning 50 minutes before, warning sound 10 seconds. Caution remains until acknowledged or reporting. Warning flashes until Stop or 10 minutes, even after its sound ends."))
        content.addView(label("Duty codes to skip (comma separated). OFF is always skipped."))
        excluded = EditText(this).apply { setSingleLine(); setText(s.excludedDutyCodes); filters = arrayOf(android.text.InputFilter.LengthFilter(1000)); content.addView(this) }
        toggle("Use event start when flight reporting/debriefing times are missing", s.allowCalendarStartAlerts) { settings, checked -> settings.copy(allowCalendarStartAlerts = checked) }
        content.addView(label("Normally incomplete flight times are flagged and skipped. AS and training use their calendar duty start. All-day entries cannot supply a departure time and are skipped."))
        content.addView(action("Save timing and duty codes") {
            val first = caution.text.toString().toIntOrNull(); val last = warning.text.toString().toIntOrNull(); val seconds = duration.text.toString().toIntOrNull()
            if (first == null || first !in 1..1440) { caution.error = "Enter 1–1440 minutes"; return@action }
            if (last == null || last !in 1..1440) { warning.error = "Enter 1–1440 minutes"; return@action }
            if (seconds == null || seconds !in 1..600) { duration.error = "Enter 1–600 seconds"; return@action }
            if (first <= last) { caution.error = "Caution must be earlier than warning (more minutes before reporting)"; return@action }
            ClockSettings.load(this).copy(cautionMinutes = first, warningMinutes = last, warningSeconds = seconds,
                excludedDutyCodes = excluded.text.toString().trim()).save(this)
            Toast.makeText(this, "Departure timing saved", Toast.LENGTH_SHORT).show(); updateStatus()
        })
        content.addView(action("Choose roster calendar / allow calendar access") { startActivity(Intent(this, ClockCalendarSettingsActivity::class.java)) })
        content.addView(action("Refresh calendar and departure alarms now") { DepartureAlerts.configure(this) })
        content.addView(label("Alarm setup", 23f))
        status = label(""); content.addView(status)
        content.addView(action("Allow alarm notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, DepartureSoundService.CHANNEL))
        })
        content.addView(action("Allow precise alarms") {
            if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else Toast.makeText(this, "Precise alarms are already available", Toast.LENGTH_SHORT).show()
        })
        content.addView(action("Allow full-screen alarms over lock screen") {
            if (Build.VERSION.SDK_INT >= 34) openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            else Toast.makeText(this, "Full-screen alarm notifications are available on this Android version", Toast.LENGTH_LONG).show()
        })
        content.addView(action("Alarm volume / sound settings") { openSettings(Intent(Settings.ACTION_SOUND_SETTINGS)) })
        content.addView(action("Do Not Disturb settings") { openSettings(Intent("android.settings.ZEN_MODE_SETTINGS")) })
        content.addView(label("Use alarm volume: this normally sounds in Silent mode. In Do Not Disturb, allow Alarms. Mirror does not change your volume or override DND. Test with your actual locked-screen and DND settings. After a reboot, unlock once so Android can read the calendar; force-stopping Mirror disables alarms until you reopen it."))
        content.addView(label("Sounds and tests", 23f))
        content.addView(label("Built-in sounds are original Airbus-inspired tones: one caution chime and a repeating warning. They are not authentic Airbus recordings. You can choose your own audio files below. A custom caution plays once, up to five seconds."))
        for (kind in DeparturePlan.Kind.entries) {
            content.addView(action("Choose ${kind.name.lowercase()} audio clip") { soundKind = kind; soundPicker.launch(arrayOf("audio/*")) })
            content.addView(action("Use built-in ${kind.name.lowercase()} sound") {
                val current = ClockSettings.load(this)
                (if (kind == DeparturePlan.Kind.CAUTION) current.copy(cautionSound = "") else current.copy(warningSound = "")).save(this)
            })
            content.addView(action("Test ${kind.name.lowercase()} · opens clock") {
                if (!DepartureAlerts.notificationsAllowed(this)) { Toast.makeText(this, "Allow alarm notifications first", Toast.LENGTH_LONG).show(); return@action }
                if (DepartureAlerts.active(this)?.test == false) { Toast.makeText(this, "A real duty alert is active. Stop it before testing.", Toast.LENGTH_LONG).show(); return@action }
                DepartureAlerts.test(this, kind)
                startActivity(Intent(this, ClockActivity::class.java).putExtra("departure", true))
            })
        }
        content.addView(action("Stop active alert") { DepartureAlerts.acknowledge(this) })
        setContentView(ScrollView(this).apply { addView(content) }); insetContent(content)
    }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "This setting is unavailable on your ROM", Toast.LENGTH_LONG).show() } }
    private fun updateStatus() {
        if (!::status.isInitialized) return
        val s = ClockSettings.load(this); val manager = getSystemService(NotificationManager::class.java)
        val full = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        val volume = getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        val p = DepartureAlerts.prefs(this)
        status.text = "Calendar: ${s.calendarName.ifBlank { "Choose calendar" }}\nCalendar access: ${ClockCalendar.hasPermission(this)}\n" +
            "Precise alarms: ${DepartureAlerts.exactAllowed(this)} · Notifications: ${DepartureAlerts.notificationsAllowed(this)}\nFull-screen alarms: $full · Alarm volume: $volume\n\n" +
            p.getString("status", "Enable departure alarms to schedule your next duty") + "\n" + p.getString("next", "") +
            p.getString("audioStatus", "").let { if (it.isNullOrBlank()) "" else "\n$it" }
    }
    override fun onResume() { super.onResume(); DepartureSoundService.createChannel(this); DepartureAlerts.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener); DepartureAlerts.configure(this); updateStatus() }
    override fun onPause() { DepartureAlerts.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("soundKind", soundKind.name); super.onSaveInstanceState(outState) }
}
