package com.mirror.app.core

import android.content.Context
import android.content.SharedPreferences

fun Context.mirrorPreferences(): SharedPreferences = getSharedPreferences("mirror", Context.MODE_PRIVATE)

class TvSettings(private val prefs: SharedPreferences) {
    var mode = prefs.getInt("mode", 0).coerceIn(0, 2)
    var zoom = prefs.getFloat("zoom", 1f).coerceIn(1f, 3f)
    var pan = prefs.getFloat("pan", 0f).coerceIn(-1f, 1f)
    var shape = prefs.getInt("shape", 2).coerceIn(0, 2)
    var size = prefs.getInt("size", 1).coerceIn(0, 2)
    var temperature = prefs.getInt("temperature", 1).coerceIn(0, 2)
    var light = prefs.getInt("light", 100).coerceIn(10, 100)
    var brightness = prefs.getInt("brightness", 0).coerceIn(-50, 50)
    var contrast = prefs.getInt("contrast", 0).coerceIn(-50, 50)
    var saturation = prefs.getInt("saturation", 0).coerceIn(-50, 50)
    var warmth = prefs.getInt("warmth", 0).coerceIn(-50, 50)
    var softFocus = prefs.getInt("softFocus", 0).coerceIn(0, 2)
    var flip = prefs.getBoolean("flip", true)
    var fill = prefs.getBoolean("fill", false)
    var rotation = prefs.getInt("rotation", 0).let { if (it in listOf(0, 90, 180, 270)) it else 0 }
    var showFps = prefs.getBoolean("showFps", false)
    var delay = prefs.getInt("delay", 5).let { if (it in listOf(3, 5, 10, 15)) it else 5 }
    var autoSleep = prefs.getInt("autoSleep", 10).let { if (it in listOf(0, 5, 10, 15, 30)) it else 10 }
    var username = prefs.getString("sourceUsername", "").orEmpty()
    var password = prefs.getString("sourcePassword", "").orEmpty()
    var cameraId = prefs.getString("selectedCameraId", "").orEmpty()
    var url = prefs.getString("url", "").orEmpty()
    fun save() {
        prefs.edit().putInt("mode", mode).putFloat("zoom", zoom).putFloat("pan", pan)
            .putInt("shape", shape).putInt("size", size).putInt("temperature", temperature)
            .putInt("light", light).putInt("brightness", brightness).putInt("contrast", contrast)
            .putInt("saturation", saturation).putInt("warmth", warmth).putInt("softFocus", softFocus)
            .putBoolean("flip", flip).putBoolean("fill", fill).putInt("rotation", rotation)
            .putBoolean("showFps", showFps).putString("url", url)
            .putInt("delay", delay).putInt("autoSleep", autoSleep).putString("sourceUsername", username)
            .putString("sourcePassword", password).putString("selectedCameraId", cameraId).apply()
    }
    fun resetFilters() { brightness = 0; contrast = 0; saturation = 0; warmth = 0; softFocus = 0 }
}

