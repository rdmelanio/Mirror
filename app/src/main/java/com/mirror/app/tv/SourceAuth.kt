package com.mirror.app.tv

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

data class SourceAuth(val token: String? = null, val username: String = "", val password: String = "") {
    fun apply(http: HttpURLConnection) {
        http.instanceFollowRedirects = false
        if (token != null) http.setRequestProperty("X-Mirror-Token", token)
        else if (username.isNotEmpty() || password.isNotEmpty()) http.setRequestProperty("Authorization", "Basic " +
            Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8)))
    }
}

class ViewerPairing(private val prefs: SharedPreferences) {
    data class Camera(val id: String, val name: String)
    val deviceId: String = prefs.getString("viewerDeviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("viewerDeviceId", it).commit()
    }
    fun token(id: String): String? = prefs.getString("token.$id", null)
    fun forget(id: String) { prefs.edit().remove("token.$id").commit() }
    private fun endpoint(address: String, path: String): HttpURLConnection = URL(address).let {
        URL(it.protocol, it.host, it.port, path).openConnection() as HttpURLConnection
    }.apply { connectTimeout = 4000; readTimeout = 4000; useCaches = false; instanceFollowRedirects = false }
    fun hello(address: String): Camera? {
        val http = endpoint(address, "/hello")
        try {
            if (http.responseCode != 200) return null
            val json = http.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (json.optString("app") != "mirror" || !json.optBoolean("requiresPairing")) return null
            val id = json.getString("cameraId"); require(id.isNotBlank())
            return Camera(id, json.optString("name", "Mirror camera"))
        } catch (_: org.json.JSONException) { return null }
        finally { http.disconnect() }
    }
    fun pair(address: String, camera: Camera, code: String, name: String): String {
        val http = endpoint(address, "/pair")
        try {
            http.requestMethod = "POST"; http.doOutput = true; http.setRequestProperty("Content-Type", "application/json")
            val bytes = JSONObject().put("code", code).put("deviceId", deviceId).put("deviceName", name).toString().toByteArray(Charsets.UTF_8)
            http.setFixedLengthStreamingMode(bytes.size); http.outputStream.use { it.write(bytes) }
            val response = http.responseCode
            val body = (if (response == 200) http.inputStream else http.errorStream)?.bufferedReader()?.use { JSONObject(it.readText()) }
            if (response == 429) error("Too many attempts")
            check(response == 200 && body != null) { body?.optString("error", "Pairing failed") ?: "Pairing failed" }
            val token = body.getString("token")
            require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid camera token" }
            prefs.edit().putString("token.${camera.id}", token).commit()
            return token
        } finally { http.disconnect() }
    }
}
