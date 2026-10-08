package com.mirror.app.phone.roster

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.mirror.app.core.mirrorPreferences
import org.json.*
import java.io.File
import java.time.*

object CaptureLog {
    @Synchronized fun add(c: Context, step: String, result: String, url: String? = null) {
        val file = File(RosterStore.dir(c), "capture.log")
        // Callers use fixed, non-personal result strings. Never include exception messages or page text.
        val path = url?.let { runCatching { Uri.parse(it).path }.getOrNull() }.orEmpty()
        val line = "${Instant.now()} $step $result ${path.substringBefore('?').substringBefore('#').replace('\n', ' ').take(200)}"
        file.writeText((if (file.exists()) file.readLines().takeLast(199) else emptyList()).plus(line).joinToString("\n"))
    }
    @Synchronized fun read(c: Context) = File(RosterStore.dir(c), "capture.log").let { if (it.exists()) it.readText() else "No capture steps" }
}
object RosterJson {
    private fun arr(items: List<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }
    fun encode(r: Roster): String = JSONObject().apply {
        put("start", r.period.start); put("end", r.period.end); put("crewId", r.crewId); put("generatedAt", r.generatedAt)
        put("memos", JSONObject(r.memos.mapKeys { it.key.toString() })); put("legend", JSONObject(r.legend))
        put("duties", arr(r.duties.map { d -> JSONObject().apply {
            put("date", d.date); put("type", d.type.name); put("code", d.code)
            put("reportLocal", d.reportLocal); put("reportInstant", d.reportInstant); put("releaseLocal", d.releaseLocal); put("releaseInstant", d.releaseInstant)
            put("releaseEstimated", d.releaseEstimated); put("delay", d.delay); put("memo", d.memo); put("memoFlag", d.memoFlag)
            put("legs", arr(d.legs.map { l -> JSONObject().apply {
                put("flightNo", l.flightNo); put("depApt", l.depApt); put("arrApt", l.arrApt); put("depTime", l.depTime); put("arrTime", l.arrTime)
                put("timeKind", l.timeKind); put("depKind", l.depKind); put("arrKind", l.arrKind); put("aircraft", l.aircraft); put("deadhead", l.deadhead)
            } }))
        } }))
    }.toString()
    private fun JSONObject.optional(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    fun decode(raw: String): Roster {
        val j = JSONObject(raw)
        val duties = objects(j.getJSONArray("duties")).map { d ->
            val legs = objects(d.getJSONArray("legs")).map { l -> Leg(l.getString("flightNo"), l.getString("depApt"), l.getString("arrApt"), LocalDateTime.parse(l.getString("depTime")), LocalDateTime.parse(l.getString("arrTime")), l.getString("timeKind"), l.optional("aircraft"), l.getBoolean("deadhead"), l.getString("depKind"), l.getString("arrKind")) }
            Duty(LocalDate.parse(d.getString("date")), DutyType.valueOf(d.getString("type")), d.getString("code"), d.optional("reportLocal")?.let { LocalDateTime.parse(it) }, d.optional("reportInstant")?.let { Instant.parse(it) }, d.optional("releaseLocal")?.let { LocalDateTime.parse(it) }, d.optional("releaseInstant")?.let { Instant.parse(it) }, d.getBoolean("releaseEstimated"), legs, d.optional("delay"), d.optional("memo"), d.optBoolean("memoFlag"))
        }
        val m = j.getJSONObject("memos"); val l = j.getJSONObject("legend")
        return Roster(Period(LocalDate.parse(j.getString("start")), LocalDate.parse(j.getString("end"))), j.getString("crewId"), Instant.parse(j.getString("generatedAt")), duties,
            m.keys().asSequence().associate { LocalDate.parse(it) to m.getString(it) }, l.keys().asSequence().associateWith { l.getString(it) })
    }
}
object RosterStore {
    @Volatile private var cache: Roster? = null
    fun phone(c: Context) = c.mirrorPreferences().getString("role", null) == "phone"
    fun prefs(c: Context) = c.getSharedPreferences("roster_link", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.noBackupFilesDir, "roster").apply { mkdirs() }
    @Synchronized fun load(c: Context): Roster? {
        if (!phone(c)) return null
        if (cache == null) cache = runCatching { RosterJson.decode(File(dir(c), "roster.json").readText()) }.getOrNull()
        return cache
    }
    @Synchronized fun accept(c: Context, bytes: ByteArray): Boolean {
        if (!phone(c)) return false
        try {
            val last = File(dir(c), "latest.pdf"); val prior = File(dir(c), "previous.pdf")
            prior.delete(); if (last.exists()) last.renameTo(prior); last.writeBytes(bytes)
            CaptureLog.add(c, "PARSE", "started")
            val fresh = RosterPdf.parse(c, bytes); val old = load(c)
            val overlap = old != null && old.crewId == fresh.crewId && fresh.period.start <= old.period.end.plusDays(1) && old.period.start <= fresh.period.end.plusDays(1)
            val merged = if (overlap) fresh.copy(period = Period(minOf(old!!.period.start, fresh.period.start), maxOf(old.period.end, fresh.period.end)),
                duties = (old.duties.filter { it.date !in fresh.period.start..fresh.period.end } + fresh.duties).sortedBy { it.reportInstant ?: it.date.atStartOfDay(AirportZones.zone("MNL")).toInstant() }, memos = old.memos.filterKeys { it !in fresh.period.start..fresh.period.end } + fresh.memos, legend = old.legend + fresh.legend) else fresh
            val atomic = AtomicFile(File(dir(c), "roster.json")); val stream = atomic.startWrite()
            try { stream.write(RosterJson.encode(merged).toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
            cache = merged; prefs(c).edit().putLong("lastSuccess", System.currentTimeMillis()).apply()
            if (old != null && old.crewId == merged.crewId) RosterDiff.summaries(old, merged).takeIf { it.isNotEmpty() }?.let { RosterNotices.post(c, 601, "Roster changed", it.take(3).joinToString(" · ")) }
            RosterAlarms.reschedule(c); RosterWork.configure(c)
            CaptureLog.add(c, "PARSE", "success"); return true
        } catch (_: Exception) { CaptureLog.add(c, "PARSE", "failed; retained last good roster"); return false }
    }
    fun stale(c: Context): Pair<String, Int>? {
        val last = prefs(c).getLong("lastSuccess", 0); if (last == 0L) return null
        val age = System.currentTimeMillis() - last
        val hours = age / 3_600_000
        return if (age > 6 * 3_600_000L) "ROSTER STALE ${hours}h" to if (age > 24 * 3_600_000L) 0xFFFF4444.toInt() else 0xFFFFB000.toInt() else null
    }
    @Synchronized fun clear(c: Context) {
        RosterWork.cancel(c); RosterAlarms.cancel(c); cache = null
        prefs(c).edit().clear().apply(); dir(c).deleteRecursively()
    }
}
