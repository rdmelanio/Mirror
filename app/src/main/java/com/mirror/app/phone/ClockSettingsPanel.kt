package com.mirror.app.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import android.widget.*
import com.mirror.app.BuildConfig
import com.mirror.app.R
import com.mirror.app.core.action
import com.mirror.app.core.dp
import com.mirror.app.core.label

class ClockSettingsPanel(private val activity: Activity, private val content: LinearLayout) {
    private var settings = ClockSettings.load(activity)
    private val preview = ClockView(activity)
    private val controller = ClockController(activity, preview, null, preview = true)
    private var alive = true
    private val refreshControls = mutableListOf<() -> Unit>()
    private fun save(value: ClockSettings) { settings = value; settings.save(activity); preview.settings = value }
    fun start() { settings = ClockSettings.load(activity); refreshControls.forEach { it() }; controller.start() }
    fun stop() { controller.stop() }
    init {
        content.addView(activity.label("Clock", 26f))
        content.addView(preview, LinearLayout.LayoutParams(-1, activity.dp(280)))
        content.addView(activity.action("Clock mode") { activity.startActivity(Intent(activity, ClockActivity::class.java)) })
        content.addView(activity.label("Clock mode can display over your secure lock screen without unlocking it. Hold empty space for 2 seconds to exit. Start the camera first to keep streaming or standby available."))
        content.addView(activity.action("Set as screen saver") {
            runCatching { activity.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }.onFailure {
                Toast.makeText(activity, "Screen saver settings are unavailable on this ROM", Toast.LENGTH_LONG).show()
            }
        })
        content.addView(activity.label("Choose Mirror Clock and set When to start: While charging"))
        choice("Style", listOf("Cockpit", "Minimal", "Stacked", "Word clock"), { settings.style }) { save(settings.copy(style = it)) }
        toggle("Show UTC time", { settings.showUtc }) { save(settings.copy(showUtc = it)) }
        content.addView(activity.label("Turn UTC off for local time only. Cockpit hides its second line; your primary-time preference is kept for when UTC is enabled again."))
        slider(content, "Clock size (% of center space)", { settings.sizePercent }, 40, 100) { save(settings.copy(sizePercent = it)) }
        content.addView(activity.label("Pinch with two fingers on the clock screen to resize. The size is saved for Clock mode and the screen saver. At 100%, the clock fills its center area. Corner information keeps its own size, with space reserved for pixel shifting."))
        toggle("Allow tap-and-hold layout editing", { settings.layoutEditing }) { save(settings.copy(layoutEditing = it)) }
        content.addView(activity.label("Tap the clock, date, alarm, weather or a duty block to select it. Hold the selected item briefly, then drag. Hold empty space for 2 seconds to exit. Positions are saved and kept inside the screen."))
        content.addView(activity.action("Reset layout · center clock and corner info") { save(settings.copy(positions = emptyMap())) })
        choice("Primary time", listOf("Local", "UTC"), { if (settings.primaryUtc) "UTC" else "Local" }) { save(settings.copy(primaryUtc = it == "UTC")) }
        choice("12/24-hour", listOf("System", "12-hour", "24-hour"), { settings.hourFormat }) { save(settings.copy(hourFormat = it)) }
        content.addView(activity.label("UTC uses aviation 24-hour Z notation. The format override applies to local time."))
        choice("Minimal font", listOf("System thin", "B612"), { settings.minimalFont }) { save(settings.copy(minimalFont = it)) }
        val presets = linkedMapOf("ECAM Green" to "#00E040", "Amber" to "#FFB000", "Cyan" to "#00E5FF", "White" to "#F2F2F2", "Magenta" to "#FF40FF", "Night Red" to "#FF2A1A")
        content.addView(activity.action("Color preset") {
            AlertDialog.Builder(activity).setTitle("Clock color").setItems(presets.keys.toTypedArray()) { _, index -> save(settings.copy(color = Color.parseColor(presets.values.elementAt(index)))) }.show()
        })
        content.addView(activity.action("Custom color · HSV") { colorPicker(settings.color) { save(settings.copy(color = it)) } })
        toggle("Gradient digits", { settings.gradient }) { save(settings.copy(gradient = it)) }
        content.addView(activity.action("Gradient second color · HSV") { colorPicker(settings.secondColor) { save(settings.copy(secondColor = it)) } })
        content.addView(activity.label("Info colors", 22f))
        content.addView(activity.action("Schedule color · HSV") { colorPicker(settings.scheduleColor) { save(settings.copy(scheduleColor = it)) } })
        content.addView(activity.action("Date color · HSV") { colorPicker(settings.dateColor) { save(settings.copy(dateColor = it)) } })
        content.addView(activity.action("Alarm color · HSV") { colorPicker(settings.alarmColor) { save(settings.copy(alarmColor = it)) } })
        content.addView(activity.action("Weather color · HSV") { colorPicker(settings.weatherColor) { save(settings.copy(weatherColor = it)) } })
        content.addView(activity.label("Night mode temporarily makes all text red. Your custom colors return in daylight."))
        content.addView(activity.label("Calendar schedule · Philippine time", 22f))
        toggle("Show calendar schedule", { settings.schedule }) {
            save(settings.copy(schedule = it))
            if (it && (!ClockCalendar.hasPermission(activity) || settings.calendarId < 0))
                activity.startActivity(Intent(activity, ClockCalendarSettingsActivity::class.java))
        }
        val calendarButton = activity.action("Choose roster calendar") {
            activity.startActivity(Intent(activity, ClockCalendarSettingsActivity::class.java))
        }
        content.addView(calendarButton)
        refreshControls += { calendarButton.text = "Roster calendar: ${settings.calendarName.ifBlank { "Choose calendar" }}" }
        content.addView(activity.action("Refresh calendar now") { controller.refreshCalendar() })
        content.addView(activity.label("Read-only. Today/overnight and tomorrow are shown with reporting–debriefing or duty times. Empty tomorrow says no calendar entry, never OFF. Refreshes every 15 minutes, at duty end, on opening and on synced changes. Google sync completion depends on Android."))
        content.addView(activity.label("Info lines", 22f))
        toggle("Date", { settings.date }) { save(settings.copy(date = it)) }
        toggle("Next alarm (hidden when none)", { settings.alarm }) { save(settings.copy(alarm = it)) }
        val weatherToggle = toggle("Weather", { settings.weather }) { save(settings.copy(weather = it && settings.city.isNotBlank())) }
        weatherToggle.isEnabled = settings.city.isNotBlank()
        val city = EditText(activity).apply { hint = "Weather city"; setSingleLine(true); setText(settings.city); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
        content.addView(city)
        val cityStatus = activity.label(if (settings.city.isBlank()) "Set a city to enable weather. No location permission is used." else settings.city)
        content.addView(cityStatus)
        val search = activity.action("Find weather city") {}
        search.setOnClickListener {
            val query = city.text.toString().trim()
            if (query.length < 2) { city.error = "Enter a city name"; return@setOnClickListener }
            search.isEnabled = false; cityStatus.text = "Finding city…"
            ClockWeather.resolve(query) { result ->
                if (!alive || activity.isFinishing || activity.isDestroyed) return@resolve
                search.isEnabled = true
                result.fold(onSuccess = { cities ->
                    if (cities.isEmpty()) cityStatus.text = "No matching city. Try a nearby city."
                    else AlertDialog.Builder(activity).setTitle("Choose weather location").setItems(cities.map { it.name }.toTypedArray()) { _, index ->
                        val found = cities[index]
                        save(settings.copy(city = found.name, latitude = found.latitude, longitude = found.longitude, weather = true))
                        city.setText(found.name); cityStatus.text = found.name; weatherToggle.isEnabled = true; weatherToggle.isChecked = true
                    }.show()
                }, onFailure = { cityStatus.text = "Could not find city. Check your connection and try again." })
            }
        }
        content.addView(search)
        toggle("Camera indicator", { settings.status }) { save(settings.copy(status = it)) }
        content.addView(activity.label("White hollow circle: camera waiting/ready. Red blinking circle: being viewed. Dim gray hollow circle: camera service off. The indicator moves with the clock; it never uses roster standby codes."))
        toggle("Seconds", { settings.seconds }) { save(settings.copy(seconds = it)) }
        content.addView(activity.label("Burn-in protection", 22f))
        toggle("Pixel shift · every minute", { settings.pixelShift }) { save(settings.copy(pixelShift = it)) }
        toggle("Slow drift", { settings.drift }) { save(settings.copy(drift = it)) }
        toggle("Hourly reposition · within the safe layout", { settings.reposition }) { save(settings.copy(reposition = it)) }
        val sensor = activity.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_LIGHT)
        toggle("Auto brightness · ambient light", { settings.autoBrightness }) { save(settings.copy(autoBrightness = it)) }
        if (sensor == null) content.addView(activity.label("No ambient light sensor: use manual brightness below. Automatic night and away modes need a light sensor."))
        slider(content, if (sensor == null) "Manual brightness (%)" else "Max brightness / manual brightness (%)", { settings.maxBrightness }, 1, 60) { save(settings.copy(maxBrightness = it)) }
        content.addView(activity.label("Light-based automation", 22f))
        toggle("Auto night · red after 60 seconds below 5 lux", { settings.autoNight }) { save(settings.copy(autoNight = it)) }
        content.addView(activity.label("The selected theme returns after 60 seconds above 15 lux. No time-of-day schedule is used."))
        toggle("Away · fully black after sustained darkness without viewers", { settings.away }) { save(settings.copy(away = it)) }
        choice("Away hours", listOf("4", "8", "12", "24"), { settings.awayHours.toString() }) { save(settings.copy(awayHours = it.toInt())) }
        content.addView(activity.label("Away resumes as soon as light rises above 5 lux. Camera streaming and standby continue while the clock is black."))
        toggle("Dim while LIVE", { settings.dimLive }) { save(settings.copy(dimLive = it)) }
        content.addView(activity.action("About Mirror") {
            val text = activity.label("Mirror ${BuildConfig.VERSION_NAME}\n\nWeather data by Open-Meteo.com\n\nB612 and B612 Mono by the B612 project. Official source: https://github.com/polarsys/b612\n\n" + activity.resources.openRawResource(R.raw.b612_ofl).bufferedReader().use { it.readText() }, 14f)
            AlertDialog.Builder(activity).setTitle("About & font license").setView(ScrollView(activity).apply { addView(text) }).setPositiveButton("Close", null).show()
        })
        content.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { alive = true }
            override fun onViewDetachedFromWindow(v: android.view.View) { alive = false; stop() }
        })
    }
    private fun toggle(name: String, read: () -> Boolean, changed: (Boolean) -> Unit): CheckBox = CheckBox(activity).apply {
        text = name; isChecked = read(); minHeight = activity.dp(48)
        setOnCheckedChangeListener { _, checked -> if (checked != read()) changed(checked) }; content.addView(this)
        refreshControls += { isChecked = read() }
    }
    private fun choice(name: String, values: List<String>, read: () -> String, changed: (String) -> Unit) {
        val button = activity.action("$name: ${read()}") {}
        button.setOnClickListener {
            AlertDialog.Builder(activity).setTitle(name).setItems(values.toTypedArray()) { _, index ->
                button.text = "$name: ${values[index]}"; changed(values[index])
            }.show()
        }
        content.addView(button)
        refreshControls += { button.text = "$name: ${read()}" }
    }
    private fun slider(parent: LinearLayout, name: String, read: () -> Int, low: Int, high: Int, changed: (Int) -> Unit) {
        val label = activity.label("$name: ${read()}"); parent.addView(label)
        val bar = SeekBar(activity).apply {
            max = high - low; progress = read() - low; contentDescription = name; minimumHeight = activity.dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$name: ${progress + low}"; if (fromUser) changed(progress + low)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        parent.addView(bar)
        if (parent === content) refreshControls += { bar.progress = read() - low; label.text = "$name: ${read()}" }
    }
    private fun colorPicker(initial: Int, changed: (Int) -> Unit) {
        val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val swatch = activity.label("Preview", 28f).apply { setTextColor(initial); setBackgroundColor(Color.BLACK) }; body.addView(swatch)
        fun update() { swatch.setTextColor(Color.HSVToColor(hsv)) }
        slider(body, "Hue", { hsv[0].toInt() }, 0, 360) { hsv[0] = it.toFloat(); update() }
        slider(body, "Saturation (%)", { (hsv[1] * 100).toInt() }, 0, 100) { hsv[1] = it / 100f; update() }
        slider(body, "Brightness (%)", { (hsv[2] * 100).toInt().coerceAtLeast(1) }, 1, 100) { hsv[2] = it / 100f; update() }
        AlertDialog.Builder(activity).setTitle("Custom color").setView(body).setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> changed(Color.HSVToColor(hsv)) }.show()
    }
}
