package com.mirror.app.phone

import android.content.Context
import org.json.JSONObject

/** The complete, versioned clock preference payload; ready for future transport. */
data class ClockSettings(
    val style: String = "Cockpit", val primaryUtc: Boolean = false,
    val hourFormat: String = "System", val minimalFont: String = "System thin",
    val color: Int = 0xFF00E040.toInt(), val gradient: Boolean = false,
    val secondColor: Int = 0xFF00E5FF.toInt(),
    val date: Boolean = true, val alarm: Boolean = true, val weather: Boolean = false,
    val city: String = "", val latitude: Double? = null, val longitude: Double? = null,
    val status: Boolean = true, val seconds: Boolean = false,
    val pixelShift: Boolean = true, val drift: Boolean = false, val reposition: Boolean = true,
    val autoBrightness: Boolean = true, val maxBrightness: Int = 60,
    val autoNight: Boolean = true, val away: Boolean = true, val awayHours: Int = 8,
    val dimLive: Boolean = true
) {
    fun json(): String = JSONObject().apply {
        put("schema", 1)
        put("style", style); put("primaryUtc", primaryUtc); put("hourFormat", hourFormat); put("minimalFont", minimalFont)
        put("color", color); put("gradient", gradient); put("secondColor", secondColor)
        put("date", date); put("alarm", alarm); put("weather", weather); put("city", city)
        put("latitude", latitude ?: JSONObject.NULL); put("longitude", longitude ?: JSONObject.NULL)
        put("status", status); put("seconds", seconds); put("pixelShift", pixelShift); put("drift", drift)
        put("reposition", reposition); put("autoBrightness", autoBrightness); put("maxBrightness", maxBrightness)
        put("autoNight", autoNight); put("away", away); put("awayHours", awayHours); put("dimLive", dimLive)
    }.toString()
    fun save(context: Context) { prefs(context).edit().putString("settings", json()).apply() }
    companion object {
        fun prefs(context: Context) = context.getSharedPreferences("mirror_clock", Context.MODE_PRIVATE)
        fun load(context: Context) = parse(prefs(context).getString("settings", null))
        fun parse(raw: String?): ClockSettings = runCatching {
            val j = JSONObject(raw ?: "{}"); val d = ClockSettings()
            fun choice(key: String, default: String, options: List<String>) = j.optString(key, default).takeIf { it in options } ?: default
            ClockSettings(
                style = choice("style", d.style, listOf("Cockpit", "Minimal", "Stacked", "Word clock")),
                primaryUtc = j.optBoolean("primaryUtc", d.primaryUtc),
                hourFormat = choice("hourFormat", d.hourFormat, listOf("System", "12-hour", "24-hour")),
                minimalFont = choice("minimalFont", d.minimalFont, listOf("System thin", "B612")),
                color = j.optInt("color", d.color) or 0xFF000000.toInt(), gradient = j.optBoolean("gradient", d.gradient),
                secondColor = j.optInt("secondColor", d.secondColor) or 0xFF000000.toInt(),
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
                dimLive = j.optBoolean("dimLive", d.dimLive)
            )
        }.getOrDefault(ClockSettings())
    }
}
