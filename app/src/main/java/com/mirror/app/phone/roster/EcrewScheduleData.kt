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
    const val STORAGE_LIMIT = 5 * 1024 * 1024
    const val HTML_LIMIT = 1024 * 1024
    fun storageMetadata(entries: JSONObject): List<String>? {
        var total = 0L
        val metadata = mutableListOf<String>()
        for (key in entries.keys()) {
            val raw = entries.opt(key) as? String ?: return null
            val size = raw.toByteArray(Charsets.UTF_8).size
            total += key.toByteArray(Charsets.UTF_8).size + size
            if (total > STORAGE_LIMIT) return null
            val value = runCatching {
                val tokener = JSONTokener(raw); val parsed = tokener.nextValue()
                if (tokener.nextClean() == '\u0000') parsed else raw
            }.getOrDefault(raw)
            val keys = (value as? JSONObject)?.keys()?.asSequence()?.toList().orEmpty()
            val array = value as? JSONArray
            val first = (array?.opt(0) as? JSONObject)?.keys()?.asSequence()?.toList().orEmpty()
            val type = when (value) { is JSONObject -> "object"; is JSONArray -> "array"; is Number -> "number"; is Boolean -> "boolean"; JSONObject.NULL -> "null"; else -> "string" }
            metadata += "key=${EcrewSnapshot.text(key)} size=$size type=$type keys=${keys.take(40).map(EcrewSnapshot::text)} firstArrayKeys=${first.take(40).map(EcrewSnapshot::text)} arrayLength=${array?.length() ?: 0}"
        }
        return metadata
    }
    @Synchronized fun saveStorage(c: Context, entries: JSONObject, afterSchedule: Boolean): Boolean {
        if (!RosterStore.phone(c)) return false
        val metadata = storageMetadata(entries) ?: return false
        write(File(directory(c), "localStorage.json"), JSONObject().put("capturedAt", System.currentTimeMillis()).put("afterCrewScheduleLoad", afterSchedule).put("localStorage", entries).toString().toByteArray())
        metadata.forEach { CaptureLog.add(c, "LOCAL_STORAGE", it) }
        return afterSchedule && (entries.has("CrewInformation") || entries.has("PublishedCrewInformation"))
    }
    @Synchronized fun saveHtml(c: Context, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        if (!RosterStore.phone(c) || bytes.size > HTML_LIMIT) return
        write(File(directory(c), "CrewSchedule.html"), bytes)
        CaptureLog.add(c, "SCHEDULE_HTML", "size=${bytes.size}")
    }
    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
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
        if (!file.exists()) folder.listFiles()?.filter { it.extension == "json" && it.name != "localStorage.json" }?.sortedBy { it.lastModified() }?.let { files ->
            files.take((files.size - MAX_ENDPOINTS + 1).coerceAtLeast(0)).forEach { it.delete() }
        }
        val content = JSONObject().put("endpoint", capture.path).put("status", capture.status)
            .put("capturedAt", System.currentTimeMillis()).put("response", capture.value).toString().toByteArray()
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(content); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        CaptureLog.add(c, "SCHEDULE_DATA", "path=${EcrewSnapshot.path(capture.path)} status=${capture.status} size=${capture.size} keys=${capture.keys} firstArrayKeys=${capture.arrayKeys}")
    }
    @Synchronized fun archive(c: Context): File? {
        val files = directory(c).listFiles()?.filter { it.name in setOf("localStorage.json", "CrewSchedule.html") }.orEmpty()
        if (files.isEmpty()) return null
        val zip = File(RosterStore.dir(c), "captured-schedule-data.zip")
        ZipOutputStream(zip.outputStream()).use { output -> files.forEach { file ->
            output.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
        } }
        return zip
    }
}

