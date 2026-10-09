package com.mirror.app.phone

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.roundToInt

data class ClockPosition(val x: Float, val y: Float)

data class ClockScreenLayout(val sizePercent: Int = 90, val positions: Map<String, ClockPosition> = emptyMap(),
    val zOrder: List<String> = emptyList(), val itemSizes: Map<String, Int> = emptyMap())

/** The complete, versioned clock preference payload; ready for future transport. */
data class ClockSettings(
    val style: String = "Cockpit", val primaryUtc: Boolean = false,
    val showUtc: Boolean = true, val sizePercent: Int = 90,
    val tvIcon: Boolean = false, val tvIconSize: Int = 36,
    val layoutEditing: Boolean = true, val positions: Map<String, ClockPosition> = emptyMap(),
    val screenLayouts: Map<String, ClockScreenLayout> = emptyMap(), val timeLabel: String = "Beside",
    val hourFormat: String = "System", val minimalFont: String = "System thin",
    val color: Int = 0xFF00E040.toInt(), val gradient: Boolean = false,
    val secondColor: Int = 0xFF00E5FF.toInt(),
    val scheduleColor: Int = 0xFFF2F2F2.toInt(), val dateColor: Int = 0xFFF2F2F2.toInt(),
    val alarmColor: Int = 0xFFFFB000.toInt(), val weatherColor: Int = 0xFF00E5FF.toInt(),
    val schedule: Boolean = false, val calendarId: Long = -1, val calendarName: String = "",
    val date: Boolean = true, val alarm: Boolean = true, val weather: Boolean = false,
    val city: String = "", val latitude: Double? = null, val longitude: Double? = null,
    val status: Boolean = true, val seconds: Boolean = false,
    val pixelShift: Boolean = true, val drift: Boolean = false, val reposition: Boolean = true,
    val autoBrightness: Boolean = true, val maxBrightness: Int = 60,
    val autoNight: Boolean = true, val away: Boolean = true, val awayHours: Int = 8,
    val dimLive: Boolean = true,
    val departureSource: String = "Auto", val departureEnabled: Boolean = false, val cautionEnabled: Boolean = true, val warningEnabled: Boolean = true,
    val cautionMinutes: Int = 60, val warningMinutes: Int = 50, val warningSeconds: Int = 10,
    val excludedDutyCodes: String = "HS,HSA", val allowCalendarStartAlerts: Boolean = false,
    val cautionSound: String = "", val warningSound: String = ""
) {
    fun layoutFor(screen: String) = screenLayouts[screen] ?: ClockScreenLayout(sizePercent, positions)
    fun withLayout(screen: String, layout: ClockScreenLayout) = copy(screenLayouts = screenLayouts + (screen to layout))
    fun resized(screen: String, percent: Int) = withLayout(screen, layoutFor(screen).copy(sizePercent = percent.coerceIn(1, 100)))
    fun moved(screen: String, key: String, position: ClockPosition) = withLayout(screen, layoutFor(screen).let { it.copy(positions = it.positions + (key to position)) })
    fun front(screen: String, key: String) = withLayout(screen, layoutFor(screen).let { it.copy(zOrder = ClockLayout.bringToFront(it.zOrder, key)) })
    fun resetLayout() = copy(sizePercent = 90, positions = emptyMap(), screenLayouts = emptyMap(), timeLabel = "Beside", tvIconSize = 36)
    val usesUtc: Boolean get() = showUtc && primaryUtc
    fun json(): String = JSONObject().apply {
        put("schema", 8); put("tvIcon", tvIcon); put("tvIconSize", tvIconSize); put("showUtc", showUtc); put("sizePercent", sizePercent)
        put("layoutEditing", layoutEditing); put("timeLabel", timeLabel)
        put("screenLayouts", JSONObject().apply {
            screenLayouts.forEach { (screen, layout) -> put(screen, JSONObject().apply {
                put("sizePercent", layout.sizePercent); put("zOrder", JSONArray(layout.zOrder)); put("itemSizes", JSONObject(layout.itemSizes))
                put("positions", JSONObject().apply { layout.positions.forEach { (key, position) -> put(key, JSONObject().put("x", position.x).put("y", position.y)) } })
            }) }
        })
        put("positions", JSONObject().apply {
            positions.forEach { (key, value) -> put(key, JSONObject().put("x", value.x).put("y", value.y)) }
        })
        put("scheduleColor", scheduleColor); put("dateColor", dateColor); put("alarmColor", alarmColor); put("weatherColor", weatherColor)
        put("schedule", schedule); put("calendarId", calendarId); put("calendarName", calendarName)
        put("departureSource", departureSource); put("departureEnabled", departureEnabled); put("cautionEnabled", cautionEnabled); put("warningEnabled", warningEnabled)
        put("cautionMinutes", cautionMinutes); put("warningMinutes", warningMinutes); put("warningSeconds", warningSeconds)
        put("excludedDutyCodes", excludedDutyCodes); put("allowCalendarStartAlerts", allowCalendarStartAlerts)
        put("cautionSound", cautionSound); put("warningSound", warningSound)
        put("style", style); put("primaryUtc", primaryUtc); put("hourFormat", hourFormat); put("minimalFont", minimalFont)
        put("color", color); put("gradient", gradient); put("secondColor", secondColor)
        put("date", date); put("alarm", alarm); put("weather", weather); put("city", city)
        put("latitude", latitude ?: JSONObject.NULL); put("longitude", longitude ?: JSONObject.NULL)
        put("status", status); put("seconds", seconds); put("pixelShift", pixelShift); put("drift", drift)
        put("reposition", reposition); put("autoBrightness", autoBrightness); put("maxBrightness", maxBrightness)
        put("autoNight", autoNight); put("away", away); put("awayHours", awayHours); put("dimLive", dimLive)
    }.toString()
    fun save(context: Context) {
        val previous = load(context)
        prefs(context).edit().putString("settings", json()).apply()
        if (DepartureAlerts.configuration(previous) != DepartureAlerts.configuration(this)) DepartureAlerts.configure(context)
    }
    companion object {
        fun prefs(context: Context) = context.getSharedPreferences("mirror_clock", Context.MODE_PRIVATE)
        fun load(context: Context) = parse(prefs(context).getString("settings", null))
        fun parse(raw: String?): ClockSettings = runCatching {
            val j = JSONObject(raw ?: "{}"); val d = ClockSettings()
            fun positions(value: JSONObject?): Map<String, ClockPosition> = ClockLayout.items.mapNotNull { key ->
                val position = value?.optJSONObject(key) ?: return@mapNotNull null
                val x = position.optDouble("x"); val y = position.optDouble("y")
                if (!x.isFinite() || !y.isFinite()) null else key to ClockPosition(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f))
            }.toMap()
            fun layouts(): Map<String, ClockScreenLayout> {
                val value = j.optJSONObject("screenLayouts") ?: return emptyMap()
                return value.keys().asSequence().filter { Regex("^(portrait|landscape)-[0-9]{1,4}x[0-9]{1,4}$").matches(it) }.take(16).mapNotNull { key ->
                    val layout = value.optJSONObject(key) ?: return@mapNotNull null
                    val order = layout.optJSONArray("zOrder")
                    val items = (0 until (order?.length() ?: 0)).map { order!!.optString(it) }.filter { it in ClockLayout.items }.distinct()
                    val sizes = layout.optJSONObject("itemSizes")
                    key to ClockScreenLayout(layout.optInt("sizePercent", d.sizePercent).coerceIn(1, 100), positions(layout.optJSONObject("positions")), items,
                        ClockLayout.items.filter { sizes?.has(it) == true }.associateWith { sizes!!.optInt(it, 100).coerceIn(1, 400) })
                }.toMap()
            }
            fun choice(key: String, default: String, options: List<String>) = j.optString(key, default).takeIf { it in options } ?: default
            ClockSettings(
                style = choice("style", d.style, listOf("Cockpit", "Minimal", "Stacked", "Word clock")),
                primaryUtc = j.optBoolean("primaryUtc", d.primaryUtc),
                showUtc = j.optBoolean("showUtc", d.showUtc),
                sizePercent = j.optInt("sizePercent", d.sizePercent).coerceIn(1, 100),
                tvIcon = j.optBoolean("tvIcon", d.tvIcon), tvIconSize = j.optInt("tvIconSize", d.tvIconSize).coerceIn(24, 96),
                layoutEditing = j.optBoolean("layoutEditing", true),
                positions = positions(j.optJSONObject("positions")), screenLayouts = layouts(),
                timeLabel = choice("timeLabel", "Beside", listOf("Beside", "Above", "Hidden")),
                hourFormat = choice("hourFormat", d.hourFormat, listOf("System", "12-hour", "24-hour")),
                minimalFont = choice("minimalFont", d.minimalFont, listOf("System thin", "B612")),
                color = j.optInt("color", d.color) or 0xFF000000.toInt(), gradient = j.optBoolean("gradient", d.gradient),
                secondColor = j.optInt("secondColor", d.secondColor) or 0xFF000000.toInt(),
                scheduleColor = j.optInt("scheduleColor", d.scheduleColor) or 0xFF000000.toInt(),
                dateColor = j.optInt("dateColor", j.optInt("color", d.dateColor)) or 0xFF000000.toInt(),
                alarmColor = j.optInt("alarmColor", j.optInt("color", d.alarmColor)) or 0xFF000000.toInt(),
                weatherColor = j.optInt("weatherColor", j.optInt("color", d.weatherColor)) or 0xFF000000.toInt(),
                schedule = j.optBoolean("schedule", false), calendarId = j.optLong("calendarId", -1).coerceAtLeast(-1),
                calendarName = j.optString("calendarName", "").take(200),
                date = j.optBoolean("date", d.date), alarm = j.optBoolean("alarm", d.alarm), weather = j.optBoolean("weather", d.weather),
                city = j.optString("city", "").take(200),
                latitude = j.optDouble("latitude").takeIf { it.isFinite() && it in -90.0..90.0 },
                longitude = j.optDouble("longitude").takeIf { it.isFinite() && it in -180.0..180.0 },
                status = j.optBoolean("status", d.status), seconds = j.optBoolean("seconds", d.seconds),
                pixelShift = j.optBoolean("pixelShift", d.pixelShift), drift = j.optBoolean("drift", d.drift),
                reposition = j.optBoolean("reposition", d.reposition), autoBrightness = j.optBoolean("autoBrightness", d.autoBrightness),
                maxBrightness = j.optInt("maxBrightness", d.maxBrightness).coerceIn(1, 60),
                autoNight = j.optBoolean("autoNight", d.autoNight), away = j.optBoolean("away", d.away),
                awayHours = j.optInt("awayHours", d.awayHours).takeIf { it in listOf(4, 8, 12, 24) } ?: 8,
                dimLive = j.optBoolean("dimLive", d.dimLive),
                departureSource = choice("departureSource", "Auto", listOf("Auto", "Calendar", "eCrew")),
                departureEnabled = j.optBoolean("departureEnabled", false),
                cautionEnabled = j.optBoolean("cautionEnabled", true), warningEnabled = j.optBoolean("warningEnabled", true),
                cautionMinutes = j.optInt("cautionMinutes", 60).coerceIn(1, 1440),
                warningMinutes = j.optInt("warningMinutes", 50).coerceIn(1, 1440),
                warningSeconds = j.optInt("warningSeconds", 10).coerceIn(1, 600),
                excludedDutyCodes = j.optString("excludedDutyCodes", "HS,HSA").take(1000),
                allowCalendarStartAlerts = j.optBoolean("allowCalendarStartAlerts", false),
                cautionSound = j.optString("cautionSound", "").take(4000), // v1.8 adopts the supplied recording; subsequent custom selections are retained.
                warningSound = if (j.optInt("schema", 0) < 6) "" else j.optString("warningSound", "").take(4000)
            )
        }.getOrDefault(ClockSettings())
    }
}


/** These are clock-face choices, never menu colors. */
object ClockColorPresets {
    val values = linkedMapOf("ECAM Green" to "#00E040", "Amber" to "#FFB000", "Cyan" to "#00E5FF", "White" to "#F2F2F2", "Magenta" to "#FF40FF", "Night Red" to "#FF2A1A")
}

/** Window bounds include system bars, so menus and the immersive clock share one profile. */
object ClockScreenKey {
    fun current(context: Context): String {
        val density = context.resources.displayMetrics.density
        val bounds = runCatching {
            val manager = context.getSystemService(android.view.WindowManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds
            else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics)
                android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
        }.getOrNull()
        return if (bounds != null) ClockLayout.screenKey((bounds.width() / density).roundToInt(), (bounds.height() / density).roundToInt())
            else ClockLayout.screenKey(context.resources.configuration.screenWidthDp, context.resources.configuration.screenHeightDp)
    }
}
