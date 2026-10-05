package com.mirror.app.tv

import org.json.JSONObject
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Separate from MJPEG read/decode; all commands are serialized in request order. */
class CameraRemote(address: String) {
    private val base = URL(address).let { URL(it.protocol, it.host, it.port, "/") }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    fun probe(callback: (JSONObject?, Boolean?) -> Unit) {
        if (closed) return
        worker.execute {
            try {
                val result = request("status")
                if (!closed) callback(result, result.optBoolean("controls") && result.has("minZoom") && result.has("maxZoom"))
            } catch (e: UnsupportedSource) { if (!closed) callback(null, false) }
            catch (_: JSONException) { if (!closed) callback(null, false) }
            catch (_: Exception) { if (!closed) callback(null, null) } // Network failure is not an unsupported API.
        }
    }
    fun control(query: String, callback: (JSONObject?, String?, Boolean) -> Unit) {
        if (closed) return
        worker.execute {
            try {
                val json = request("control?$query")
                if (!closed) callback(json, if (json.optBoolean("ok")) null else json.optString("error", "Camera control failed"), false)
            } catch (e: UnsupportedSource) { if (!closed) callback(null, "Camera controls unavailable for this source", true) }
            catch (e: Exception) { if (!closed) callback(null, e.message ?: "Can't reach camera", false) }
        }
    }
    private class UnsupportedSource : Exception()
    private fun request(path: String): JSONObject {
        val http = URL(base, path).openConnection() as HttpURLConnection
        connection = http
        try {
            http.connectTimeout = 4000; http.readTimeout = 14000; http.useCaches = false
            if (closed) error("Camera connection closed")
            val code = http.responseCode
            if (code == 404 || code == 405 || code == 501) throw UnsupportedSource()
            val stream = if (code in 200..299) http.inputStream else http.errorStream
            val json = stream?.bufferedReader()?.use { JSONObject(it.readText()) }
                ?: error("Camera returned HTTP $code")
            return json
        } finally { http.disconnect(); connection = null }
    }
    fun close() { closed = true; connection?.disconnect(); worker.shutdownNow() }
}
