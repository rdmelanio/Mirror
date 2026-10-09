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

class DepartureSettingsPanel(private val activity: ComponentActivity, private val content: LinearLayout) {
    private lateinit var status: TextView
    private lateinit var caution: EditText
    private lateinit var warning: EditText
    private lateinit var duration: EditText
    private lateinit var excluded: EditText
    private var refreshSource: () -> Unit = {}
    private var calendarButton: Button? = null
    private var soundKind = DeparturePlan.Kind.valueOf(DepartureAlerts.prefs(activity).getString("soundPickerKind", "CAUTION") ?: "CAUTION")
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateStatus() }
    private val permissions = activity.activityResultRegistry.register("departure.permission", ActivityResultContracts.RequestPermission()) { updateStatus(); DepartureAlerts.configure(activity) }
    private val soundPicker = activity.activityResultRegistry.register("departure.sound", ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.fold(onSuccess = {
                val s = ClockSettings.load(activity)
                (if (soundKind == DeparturePlan.Kind.CAUTION) s.copy(cautionSound = uri.toString()) else s.copy(warningSound = uri.toString())).save(activity)
                Toast.makeText(activity, "Audio clip saved · use Test to check it", Toast.LENGTH_LONG).show()
            }, onFailure = { Toast.makeText(activity, "Could not keep access to that clip. Choose a local audio file.", Toast.LENGTH_LONG).show() })
        }
    }
    init {
        val s = ClockSettings.load(activity)
        content.addView(activity.label("Leave for duty", 28f))
        content.addView(activity.label("Two departure alarms from your selected roster source. Flights, AS and timed training are included; OFF, HS and HSA are skipped by default. Alarms work without leaving Clock mode open."))
        fun toggle(title: String, value: Boolean, change: (ClockSettings, Boolean) -> ClockSettings) {
            content.addView(PhoneTheme.switch(activity, checkbox = true).apply {
                text = title; isChecked = value; minHeight = activity.dp(48)
                setOnCheckedChangeListener { _, checked -> change(ClockSettings.load(activity), checked).save(activity); updateStatus() }
            })
        }
        refreshSource = RosterSourceSelector.add(activity, content) { updateStatus() }
        toggle("Enable departure alarms", s.departureEnabled) { settings, checked -> settings.copy(departureEnabled = checked) }
        toggle("Master caution · single chime and persistent amber", s.cautionEnabled) { settings, checked -> settings.copy(cautionEnabled = checked) }
        toggle("Master warning · repeating sound and flashing red", s.warningEnabled) { settings, checked -> settings.copy(warningEnabled = checked) }
        fun number(title: String, value: Int): EditText {
            content.addView(activity.label(title))
            return EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText(value.toString()); contentDescription = title; content.addView(this) }
        }
        caution = number("Caution: minutes before reporting (1–1440)", s.cautionMinutes)
        warning = number("Warning: minutes before reporting (1–1440)", s.warningMinutes)
        duration = number("Warning sound: seconds (1–600)", s.warningSeconds)
        content.addView(activity.label("Default: caution 60 minutes before, warning 50 minutes before, warning sound 10 seconds. Caution remains until acknowledged or reporting. Warning flashes until Stop or 10 minutes, even after its sound ends."))
        content.addView(activity.label("Duty codes to skip (comma separated). OFF is always skipped."))
        excluded = EditText(activity).apply { setSingleLine(); setText(s.excludedDutyCodes); filters = arrayOf(android.text.InputFilter.LengthFilter(1000)); content.addView(this) }
        toggle("Use event start when flight reporting/debriefing times are missing", s.allowCalendarStartAlerts) { settings, checked -> settings.copy(allowCalendarStartAlerts = checked) }
        content.addView(activity.label("Normally incomplete flight times are flagged and skipped. AS and training use their calendar duty start. All-day entries cannot supply a departure time and are skipped."))
        content.addView(activity.action("Save timing and duty codes") {
            val first = caution.text.toString().toIntOrNull(); val last = warning.text.toString().toIntOrNull(); val seconds = duration.text.toString().toIntOrNull()
            if (first == null || first !in 1..1440) { caution.error = "Enter 1–1440 minutes"; return@action }
            if (last == null || last !in 1..1440) { warning.error = "Enter 1–1440 minutes"; return@action }
            if (seconds == null || seconds !in 1..600) { duration.error = "Enter 1–600 seconds"; return@action }
            if (first <= last) { caution.error = "Caution must be earlier than warning (more minutes before reporting)"; return@action }
            ClockSettings.load(activity).copy(cautionMinutes = first, warningMinutes = last, warningSeconds = seconds,
                excludedDutyCodes = excluded.text.toString().trim()).save(activity)
            Toast.makeText(activity, "Departure timing saved", Toast.LENGTH_SHORT).show(); updateStatus()
        })
        calendarButton = activity.action("Choose roster calendar / allow calendar access") { activity.startActivity(Intent(activity, ClockCalendarSettingsActivity::class.java)) }; content.addView(calendarButton)
        content.addView(activity.action("Refresh selected roster departure alarms now") { DepartureAlerts.configure(activity) })
        content.addView(activity.label("Alarm setup", 23f))
        status = activity.label(""); content.addView(status)
        content.addView(activity.action("Allow alarm notifications") {
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, DepartureSoundService.CHANNEL))
        })
        content.addView(activity.action("Allow precise alarms") {
            if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$activity.packageName")))
            else Toast.makeText(activity, "Precise alarms are already available", Toast.LENGTH_SHORT).show()
        })
        content.addView(activity.action("Allow full-screen alarms over lock screen") {
            if (Build.VERSION.SDK_INT >= 34) openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$activity.packageName")))
            else Toast.makeText(activity, "Full-screen alarm notifications are available on this Android version", Toast.LENGTH_LONG).show()
        })
        content.addView(activity.action("Alarm volume / sound settings") { openSettings(Intent(Settings.ACTION_SOUND_SETTINGS)) })
        content.addView(activity.action("Do Not Disturb settings") { openSettings(Intent("android.settings.ZEN_MODE_SETTINGS")) })
        content.addView(activity.label("Use alarm volume: this normally sounds in Silent mode. In Do Not Disturb, allow Alarms. Mirror does not change your volume or override DND. Test with your actual locked-screen and DND settings. After a reboot, unlock once so Android can read the calendar; force-stopping Mirror disables alarms until you reopen it."))
        content.addView(activity.label("Sounds and tests", 23f))
        content.addView(activity.label("The default master warning uses your supplied Airbus recording, prepared as a seamless hardware PCM loop. The caution is an original single chime. You can choose your own audio files below. A custom caution plays once, up to five seconds."))
        for (kind in DeparturePlan.Kind.entries) {
            content.addView(activity.action("Choose ${kind.name.lowercase()} audio clip") { soundKind = kind; DepartureAlerts.prefs(activity).edit().putString("soundPickerKind", kind.name).apply(); soundPicker.launch(arrayOf("audio/*")) })
            content.addView(activity.action("Use ${if (kind == DeparturePlan.Kind.WARNING) "supplied Airbus warning" else "built-in caution chime"}") {
                val current = ClockSettings.load(activity)
                (if (kind == DeparturePlan.Kind.CAUTION) current.copy(cautionSound = "") else current.copy(warningSound = "")).save(activity)
            })
            content.addView(activity.action("Test ${kind.name.lowercase()} · opens clock") {
                if (!DepartureAlerts.notificationsAllowed(activity)) { Toast.makeText(activity, "Allow alarm notifications first", Toast.LENGTH_LONG).show(); return@action }
                if (DepartureAlerts.active(activity)?.test == false) { Toast.makeText(activity, "A real duty alert is active. Stop it before testing.", Toast.LENGTH_LONG).show(); return@action }
                DepartureAlerts.test(activity, kind)
                activity.startActivity(Intent(activity, ClockActivity::class.java).putExtra("departure", true))
            })
        }
        content.addView(activity.action("Stop active alert") { DepartureAlerts.acknowledge(activity) })
    }
    private fun openSettings(intent: Intent) { runCatching { activity.startActivity(intent) }.onFailure { Toast.makeText(activity, "This setting is unavailable on your ROM", Toast.LENGTH_LONG).show() } }
    private fun updateStatus() {
        if (!::status.isInitialized) return
        refreshSource(); calendarButton?.visibility = if (DepartureRosterSource.select(activity) == DepartureSourcePolicy.Source.CALENDAR) android.view.View.VISIBLE else android.view.View.GONE
        val s = ClockSettings.load(activity); val manager = activity.getSystemService(NotificationManager::class.java)
        val full = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        val volume = activity.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        val p = DepartureAlerts.prefs(activity)
        status.text = "Roster source: ${RosterSourceSelector.title(activity)}\n" + (if (DepartureRosterSource.select(activity) == DepartureSourcePolicy.Source.CALENDAR) "Calendar: ${s.calendarName.ifBlank { "Choose calendar" }}\nCalendar access: ${ClockCalendar.hasPermission(activity)}\n" else "Printed eCrew duty report times\n") +
            "Precise alarms: ${DepartureAlerts.exactAllowed(activity)} · Notifications: ${DepartureAlerts.notificationsAllowed(activity)}\nFull-screen alarms: $full · Alarm volume: $volume\n\n" +
            p.getString("status", "Enable departure alarms to schedule your next duty") + "\n" + p.getString("next", "") +
            p.getString("audioStatus", "").let { if (it.isNullOrBlank()) "" else "\n$it" }
    }
    fun start() { DepartureSoundService.createChannel(activity); DepartureAlerts.prefs(activity).registerOnSharedPreferenceChangeListener(prefsListener); DepartureAlerts.configure(activity); updateStatus() }
    fun stop() { DepartureAlerts.prefs(activity).unregisterOnSharedPreferenceChangeListener(prefsListener) }
    fun dispose() { stop(); permissions.unregister(); soundPicker.unregister() }
}

class DepartureSettingsActivity : ComponentActivity() {
    private lateinit var panel: DepartureSettingsPanel
    override fun onCreate(savedInstanceState: Bundle?) {
        PhoneTheme.install(this); super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_ALARM
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = DepartureSettingsPanel(this, content)
        content.addView(PhoneTheme.appBar(this, "Departure alarms") { finish() }, 0); PhoneTheme.groupSettings(content); PhoneUi.style(content); setContentView(ScrollView(this).apply { addView(content) }); insetContent(content)
    }
    override fun onResume() { super.onResume(); panel.start() }
    override fun onPause() { panel.stop(); super.onPause() }
    override fun onDestroy() { panel.dispose(); super.onDestroy() }
}

