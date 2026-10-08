package com.mirror.app.phone.roster

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Period(val start: LocalDate, val end: LocalDate)
enum class DutyType { FLIGHT, STANDBY, OFF, LEAVE, OTHER }
data class Leg(val flightNo: String, val depApt: String, val arrApt: String,
    val depTime: LocalDateTime, val arrTime: LocalDateTime, val timeKind: String,
    val aircraft: String?, val deadhead: Boolean, val depKind: String = timeKind, val arrKind: String = timeKind)
data class Duty(val date: LocalDate, val type: DutyType, val code: String,
    val reportLocal: LocalDateTime? = null, val reportInstant: Instant? = null,
    val releaseLocal: LocalDateTime? = null, val releaseInstant: Instant? = null,
    val releaseEstimated: Boolean = false, val legs: List<Leg> = emptyList(),
    val delay: String? = null, val memo: String? = null, val memoFlag: Boolean = false) {
    val key get() = "$date:$code:${legs.firstOrNull()?.flightNo.orEmpty()}:${reportLocal?.toLocalTime()}"
}
data class Roster(val period: Period, val crewId: String, val generatedAt: Instant,
    val duties: List<Duty>, val memos: Map<LocalDate, String> = emptyMap(), val legend: Map<String, String> = emptyMap())
data class Word(val text: String, val xMin: Float, val xMax: Float, val yMin: Float)
object RosterText {
    fun clean(s: String) = s.replace("\u200B", "").replace("\uFEFF", "").trim()
    fun bin(words: List<Word>, headers: List<Word>, weekdayY: Float, bottom: Float): List<List<String>> =
        headers.sortedBy { it.xMin }.map { h ->
            val center = (h.xMin + h.xMax) / 2
            words.filter { it.yMin > weekdayY && it.yMin < bottom }.filter { w ->
                val x = (w.xMin + w.xMax) / 2
                headers.minByOrNull { kotlin.math.abs((it.xMin + it.xMax) / 2 - x) } == h && kotlin.math.abs(x - center) <= 13f
            }.sortedWith(compareBy<Word> { it.yMin }.thenBy { it.xMin }).map { clean(it.text) }.filter { it.isNotEmpty() }
        }
}
object AirportZones {
    private val regional = mapOf(
        "HKG" to "Asia/Hong_Kong", "MFM" to "Asia/Macau", "TPE" to "Asia/Taipei", "KHH" to "Asia/Taipei",
        "NRT" to "Asia/Tokyo", "HND" to "Asia/Tokyo", "KIX" to "Asia/Tokyo", "NGO" to "Asia/Tokyo", "FUK" to "Asia/Tokyo", "CTS" to "Asia/Tokyo",
        "ICN" to "Asia/Seoul", "PUS" to "Asia/Seoul", "PVG" to "Asia/Shanghai", "PEK" to "Asia/Shanghai", "CAN" to "Asia/Shanghai", "XMN" to "Asia/Shanghai",
        "SIN" to "Asia/Singapore", "KUL" to "Asia/Kuala_Lumpur", "BKI" to "Asia/Kuching", "BKK" to "Asia/Bangkok", "DMK" to "Asia/Bangkok", "HKT" to "Asia/Bangkok",
        "HAN" to "Asia/Ho_Chi_Minh", "SGN" to "Asia/Ho_Chi_Minh", "DAD" to "Asia/Ho_Chi_Minh", "CGK" to "Asia/Jakarta", "DPS" to "Asia/Makassar",
        "SYD" to "Australia/Sydney", "MEL" to "Australia/Melbourne", "DXB" to "Asia/Dubai")
    private val philippines = "MNL CEB CRK DVO ZAM TAG BCD CGY MPH TUG ILO KLO PPS DGT TAC WNP LGP DRP LAO BSO USU IAO SJI SUG DPL PAG GES OZC CBO RXS CRM SFS ENI MBT VRC RPL BAG BQA CYZ TBH BXU AAV CCG CUJ CYU IGN IPE JOL LWA MXI NCP PUG SFE SGS XSO WDS TWT DTE DUM DSB DGP LLC LLB PPR RZP UEP".split(' ').toSet()
    fun zone(apt: String, warn: () -> Unit = {}): ZoneId {
        val a = apt.removePrefix("*")
        if (a in regional) return ZoneId.of(regional.getValue(a))
        if (a !in philippines) warn()
        return ZoneId.of("Asia/Manila")
    }
}

/** No Android dependency: malformed/unfinished duties reject a capture, preserving the last good roster. */
object RosterGrammar {
    private val time = Regex("^[AE]?\\d{2}:\\d{2}$")
    private val flight = Regex("^\\d{1,4}[A-Z]?$")
    private val airport = Regex("^\\*?[A-Z]{3}$")
    private val aircraft = Regex("^\\[[0-9A-Z]{3}]$")
    private val weekdays = setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private data class Token(val text: String, val day: LocalDate)
    fun parse(columns: Map<LocalDate, List<String>>, warn: () -> Unit = {}): List<Duty> {
        // A continuation is one stream; day annotations preserve midnight boundaries, including M.
        val tokens = columns.toSortedMap().flatMap { (day, values) -> values.map { Token(RosterText.clean(it), day) }
            .filter { it.text.isNotEmpty() && it.text !in weekdays && !Regex("^\\d{2}/\\d{2}$").matches(it.text) } }
        val result = mutableListOf<Duty>(); var i = 0
        val memoDays = tokens.filter { it.text == "M" }.map { it.day }.toSet()
        fun peek(offset: Int = 0) = tokens.getOrNull(i + offset)?.text.orEmpty()
        fun skipMarks() { while (peek() in listOf("→", "↓", "M")) i++ }
        fun isTime(s: String) = time.matches(s)
        fun isFlight(s: String) = flight.matches(s)
        while (i < tokens.size) {
            skipMarks(); if (i >= tokens.size) break
            val first = tokens[i]; var previous: LocalDateTime? = null
            fun readTime(): Pair<LocalDateTime, String> {
                skipMarks(); val t = tokens.getOrNull(i++) ?: error("Missing time")
                require(isTime(t.text)) { "Invalid time" }
                val kind = if (t.text.startsWith("A") || t.text.startsWith("E")) t.text.take(1) else "S"
                val value = LocalTime.parse(t.text.takeLast(5))
                var local = maxOf(first.day, t.day, previous?.toLocalDate() ?: first.day).atTime(value)
                if (previous != null && local < previous!!) local = local.plusDays(1)
                previous = local; return local to kind
            }
            if (!isTime(first.text)) {
                require(Regex("^[A-Z]{2,5}$").matches(first.text)) { "Unknown token" }
                val code = first.text; i++
                val type = when (code) { "OFF" -> DutyType.OFF; "RVL" -> DutyType.LEAVE; "AS", "HSA", "HS" -> DutyType.STANDBY; else -> DutyType.OTHER }
                val report = if (isTime(peek()) && tokens[i].day == first.day) readTime().first else null
                val continued = peek() == "→"; skipMarks()
                val release = if (report != null && isTime(peek()) && (continued || (tokens[i].day == first.day && !isFlight(peek(1))))) readTime().first else null
                require(!continued || release != null) { "Incomplete continuation" }
                result += Duty(first.day, type, code, report, report?.atZone(AirportZones.zone("MNL"))?.toInstant(),
                    release, release?.atZone(AirportZones.zone("MNL"))?.toInstant(), memoFlag = first.day in memoDays)
                continue
            }
            val report = readTime().first
            val legs = mutableListOf<Leg>()
            while (isFlight(peek())) {
                val number = peek(); i++
                val dep = readTime(); skipMarks()
                val depApt = peek(); require(airport.matches(depApt)) { "Missing departure airport" }; i++; skipMarks()
                val arrApt = peek(); require(airport.matches(arrApt)) { "Missing arrival airport" }; i++
                val arr = readTime(); skipMarks()
                val act = if (aircraft.matches(peek())) peek().removeSurrounding("[", "]").also { i++ } else null
                val deadhead = depApt.startsWith("*") || arrApt.startsWith("*")
                require(act != null || deadhead) { "Missing aircraft" }
                legs += Leg(number, depApt.removePrefix("*"), arrApt.removePrefix("*"), dep.first, arr.first,
                    if (dep.second == arr.second) dep.second else "${dep.second}/${arr.second}", act, deadhead, dep.second, arr.second)
                skipMarks()
            }
            require(legs.isNotEmpty()) { "Missing flight legs" }
            val delay = if (peek() == "Delay") { i++; require(isTime(peek())); peek().takeLast(5).also { i++ } } else null
            skipMarks()
            // TIME FLT is a second report; a time alone belongs to this duty only on its arrival day.
            val release = if (isTime(peek()) && !isFlight(peek(1)) && tokens[i].day <= previous!!.toLocalDate()) readTime().first else null
            val estimated = release == null && legs.last().deadhead
            require(release != null || estimated) { "Missing release" }
            val finalRelease = release ?: legs.last().arrTime.plusMinutes(30)
            result += Duty(first.day, DutyType.FLIGHT, "FLIGHT", report, report.atZone(AirportZones.zone(legs.first().depApt, warn)).toInstant(),
                finalRelease, finalRelease.atZone(AirportZones.zone(legs.last().arrApt, warn)).toInstant(), estimated, legs, delay, memoFlag = first.day in memoDays)
        }
        return result
    }
}

object RosterDiff {
    fun summaries(old: Roster, fresh: Roster): List<String> {
        val dates = (old.duties.map { it.date } + fresh.duties.map { it.date }).distinct().sorted()
        return dates.mapNotNull { date ->
            val a = old.duties.filter { it.date == date }; val b = fresh.duties.filter { it.date == date }
            if (a == b && old.memos[date] == fresh.memos[date]) null else {
                val removed = a.sumOf { it.legs.size } - b.sumOf { it.legs.size }
                val summary = if (removed > 0) "$removed legs removed" else "${label(a)} → ${label(b)}"
                "${date.format(DateTimeFormatter.ofPattern("dd/MM"))}: $summary"
            }
        }
    }
    private fun label(d: List<Duty>) = d.joinToString(" + ") { it.legs.firstOrNull()?.let { leg -> "5J${leg.flightNo} RPT ${it.reportLocal?.toLocalTime()}" } ?: it.code }.ifEmpty { "no duty" }
}
data class RosterAlarm(val id: Int, val label: String = "PREPARE", val offset: Int = 80, val enabled: Boolean = true)
data class PlannedAlarm(val key: String, val at: Instant, val alarm: RosterAlarm, val duty: Duty)
object RosterAlarmPlan {
    fun plan(duties: List<Duty>, alarms: List<RosterAlarm>, enabledCodes: (Duty) -> Boolean,
        skipped: Set<String> = emptySet()): List<PlannedAlarm> = duties.filter { it.reportInstant != null && enabledCodes(it) }
        .flatMap { d -> alarms.filter { it.enabled }.map { a -> PlannedAlarm("${d.date}:${d.code}:${d.legs.firstOrNull()?.flightNo.orEmpty()}:${a.id}", d.reportInstant!!.minusSeconds(a.offset * 60L), a, d) } }
        .filter { it.key !in skipped }
    fun moved(old: Map<String, Instant>, fresh: List<PlannedAlarm>) = fresh.filter { old[it.key] != null && old[it.key] != it.at }
}
