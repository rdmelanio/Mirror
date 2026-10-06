package com.mirror.app.phone

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One bounded worker, no location permission, no work in capture/encode threads. */
object ClockWeather {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var busy = false
    private var attempted = 0L
    private var attemptedCity = ""
    private var failed = false
    data class City(val name: String, val latitude: Double, val longitude: Double)
    private fun fetch(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        return try {
            require(connection.responseCode == 200) { "Weather service unavailable" }
            connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText(); require(text.length < 256_000); JSONObject(text)
            }
        } finally { connection.disconnect() }
    }
    fun resolve(name: String, result: (Result<List<City>>) -> Unit) {
        worker.execute {
            val answer = runCatching {
                val json = fetch("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=5&language=en&format=json")
                val cities = json.optJSONArray("results")
                (0 until (cities?.length() ?: 0)).map { index ->
                    val city = cities!!.getJSONObject(index)
                    City(listOf(city.getString("name"), city.optString("admin1"), city.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", "), city.getDouble("latitude"), city.getDouble("longitude"))
                }
            }
            main.post { result(answer) }
        }
    }
    fun refresh(context: Context, s: ClockSettings, done: () -> Unit) {
        if (!s.weather || s.latitude == null || s.longitude == null || busy) return
        val now = android.os.SystemClock.elapsedRealtime()
        val prefs = ClockSettings.prefs(context)
        if (s.city == attemptedCity && now - attempted < 1_800_000) return
        if (attemptedCity != s.city && prefs.getString("weatherCity", "") == s.city &&
            System.currentTimeMillis() - prefs.getLong("weatherAt", 0) in 0..1_799_999) return
        busy = true; attempted = now; attemptedCity = s.city
        val app = context.applicationContext
        worker.execute {
            val value = runCatching {
                val current = fetch("https://api.open-meteo.com/v1/forecast?latitude=${s.latitude}&longitude=${s.longitude}&current=temperature_2m,weather_code").getJSONObject("current")
                "${kotlin.math.round(current.getDouble("temperature_2m")).toInt()}°C · ${condition(current.getInt("weather_code"))}"
            }
            main.post {
                busy = false; failed = value.isFailure
                value.getOrNull()?.let {
                    ClockSettings.prefs(app).edit().putString("weatherCity", s.city).putString("weatherValue", it)
                        .putLong("weatherAt", System.currentTimeMillis()).apply()
                }
                done()
            }
        }
    }
    fun display(context: Context, s: ClockSettings): String {
        val p = ClockSettings.prefs(context)
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)
        val online = network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return if (!online || (failed && attemptedCity == s.city) || p.getString("weatherCity", "") != s.city ||
            System.currentTimeMillis() - p.getLong("weatherAt", 0) !in 0..3_600_000) "--"
        else p.getString("weatherValue", "--") ?: "--"
    }
    private fun condition(code: Int) = when (code) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
        in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> "Unknown"
    }
}
