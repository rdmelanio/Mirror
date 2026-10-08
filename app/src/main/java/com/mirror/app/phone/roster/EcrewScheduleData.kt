package com.mirror.app.phone.roster

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Raw captures are private and never fed into the roster parser or alarms. */
object EcrewScheduleData {
    const val LIMIT = 2 * 1024 * 1024
    const val MAX_ENDPOINTS = 20
    data class Capture(val path: String, val status: Int, val size: Int, val value: Any,
        val keys: List<String>, val arrayKeys: List<String>)
    fun decode(path: String, status: Int, body: String): Capture? {
        if (path.contains('?') || path.contains('#') || !EcrewPortPolicy.page("https://ecrew.cebupacificair.com$path") || status !in 100..599) return null
        val size = body.toByteArray(Charsets.UTF_8).size
        if (size >= LIMIT) return null
        return runCatching {
            val tokener = JSONTokener(body); val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') return null
            val keys = if (value is JSONObject) value.keys().asSequence().toList() else emptyList()
            val arrays = when (value) {
                is JSONArray -> listOf(value)
                is JSONObject -> keys.mapNotNull { value.optJSONArray(it) }
                else -> emptyList()
            }
            val arrayKeys = arrays.flatMap { array -> (array.opt(0) as? JSONObject)?.keys()?.asSequence()?.toList().orEmpty() }.distinct()
            Capture(path, status, size, value, keys.take(40).map(EcrewSnapshot::text), arrayKeys.take(40).map(EcrewSnapshot::text))
        }.getOrNull()
    }
    fun directory(c: Context) = File(RosterStore.dir(c), "schedule-data").apply { mkdirs() }
    @Synchronized fun save(c: Context, capture: Capture) {
        if (!RosterStore.phone(c)) return
        val folder = directory(c)
        val hash = MessageDigest.getInstance("SHA-256").digest(capture.path.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(folder, "$hash.json")
        if (!file.exists()) folder.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }?.let { files ->
            files.take((files.size - MAX_ENDPOINTS + 1).coerceAtLeast(0)).forEach { it.delete() }
        }
        val content = JSONObject().put("endpoint", capture.path).put("status", capture.status)
            .put("capturedAt", System.currentTimeMillis()).put("response", capture.value).toString().toByteArray()
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(content); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        CaptureLog.add(c, "SCHEDULE_DATA", "path=${EcrewSnapshot.path(capture.path)} status=${capture.status} size=${capture.size} keys=${capture.keys} firstArrayKeys=${capture.arrayKeys}")
    }
    @Synchronized fun archive(c: Context): File? {
        val files = directory(c).listFiles()?.filter { it.extension == "json" }?.take(MAX_ENDPOINTS).orEmpty()
        if (files.isEmpty()) return null
        val zip = File(RosterStore.dir(c), "captured-schedule-data.zip")
        ZipOutputStream(zip.outputStream()).use { output -> files.forEach { file ->
            output.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
        } }
        return zip
    }
}
