package com.mirror.app.phone

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure roster interpretation. Device time zone and the clock's UTC option never affect duties. */
object ClockRoster {
    val zone: ZoneId = ZoneId.of("Asia/Manila")
    data class Event(val id: Long, val title: String, val description: String, val begin: Long, val end: Long, val allDay: Boolean)
    data class Duty(val id: Long, val text: String, val start: Long, val end: Long, val day: LocalDate,
                    val allDay: Boolean, val calendarTimes: Boolean = false, val sourceKey: String? = null)
    data class Day(val date: LocalDate, val label: String, val duties: List<Duty>)
    private val leg = Regex("(?im)^\\s*\\d+[A-Z]?\\s*[-–—]\\s*([A-Z]{3})\\s*\\([^\\r\\n)]*\\)\\s*[-–—]\\s*([A-Z]{3})\\b")
    private val titleRoute = Regex("\\b[A-Z]{3}(?:\\s*[-–—]\\s*[A-Z]{3})+\\b")
    private fun plain(raw: String) = raw.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
    private fun dutyTime(description: String, field: String): LocalTime? {
        val match = Regex("(?im)\\b$field\\s*(?:time)?\\s*[:：]\\s*(\\d{1,2}):?(\\d{2})\\s*(AM|PM)?\\b").find(description) ?: return null
        var hour = match.groupValues[1].toInt(); val minute = match.groupValues[2].toInt()
        val period = match.groupValues[3].uppercase(Locale.ENGLISH)
        if (period.isNotEmpty()) { if (hour !in 1..12) return null; hour = hour % 12 + if (period == "PM") 12 else 0 }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
    private fun nearest(time: LocalTime, anchor: Long, after: Long? = null): Long? {
        val date = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        return (-1L..1L).map { date.plusDays(it).atTime(time).atZone(zone).toInstant().toEpochMilli() }
            .filter { after == null || it > after }.minByOrNull { kotlin.math.abs(it - anchor) }
    }
    fun parse(event: Event): Duty {
        val description = plain(event.description)
        val title = plain(event.title).replace(Regex("\\s+"), " ").trim().ifBlank { "Untitled duty" }
        if (event.allDay) {
            // CalendarContract encodes all-day dates at UTC midnight, regardless of display zone.
            val day = Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate()
            val exclusiveEnd = Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate().coerceAtLeast(day.plusDays(1))
            return Duty(event.id, code(title), day.atStartOfDay(zone).toInstant().toEpochMilli(),
                exclusiveEnd.atStartOfDay(zone).toInstant().toEpochMilli(), day, true)
        }
        val pairs = leg.findAll(description).map { it.groupValues[1].uppercase(Locale.ENGLISH) to it.groupValues[2].uppercase(Locale.ENGLISH) }.toList()
        val route = if (pairs.isNotEmpty()) buildString {
            var previous = ""
            pairs.forEach { (from, to) ->
                if (isEmpty()) append(from) else if (previous != from) append(" / ").append(from)
                append("–").append(to); previous = to
            }
        } else null
        val reporting = dutyTime(description, "Reporting")
        val debriefing = dutyTime(description, "Debriefing")
        // A complete pair is required. Never mix one parsed time with a potentially unrelated event time.
        val parsedStart = reporting?.let { nearest(it, event.begin) }
        val parsedEnd = if (parsedStart != null && debriefing != null) nearest(debriefing, event.end, parsedStart) else null
        val useParsed = parsedStart != null && parsedEnd != null && parsedEnd - parsedStart <= 72 * 3_600_000L
        val start = if (useParsed) parsedStart!! else event.begin
        val end = if (useParsed) parsedEnd!! else maxOf(event.end, event.begin + 60_000)
        val flightTitle = title.firstOrNull()?.isDigit() == true
        val text = route ?: if (flightTitle) titleRoute.find(title)?.value?.replace(Regex("\\s*[-–—]\\s*"), "–") ?: title else code(title)
        return Duty(event.id, text, start, end, Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), false,
            calendarTimes = !useParsed && (route != null || flightTitle))
    }
    private fun code(title: String): String = if (Regex("(?i)^OFF(?:\\s|$)").containsMatchIn(title)) "OFF DAY" else title.substringBefore(' ')
    fun days(duties: List<Duty>, now: Long): List<Day> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun on(day: LocalDate) = duties.filter { d -> d.day == day || (d.allDay && d.day < day && d.end > day.atStartOfDay(zone).toInstant().toEpochMilli()) }
            .sortedWith(compareBy<Duty> { it.start }.thenBy { it.id })
        val overnight = duties.filter { !it.allDay && it.day < today && it.end > now }.minByOrNull { it.start }
        val todayDuties = on(today)
        val finishedToday = todayDuties.isNotEmpty() && todayDuties.maxOf { it.end } <= now
        val focus = if (overnight != null) today else if (finishedToday) today.plusDays(1) else today
        // Keep overnight duties with today; tomorrow remains visible even after midnight.
        val second = focus.plusDays(1)
        fun label(date: LocalDate, first: Boolean) = when {
            date < today -> "OVERNIGHT"
            date == today -> "TODAY"
            date == today.plusDays(1) -> if (first) "NEXT DUTY · TOMORROW" else "TOMORROW"
            else -> "UPCOMING"
        }
        val primary = if (overnight != null) (duties.filter { !it.allDay && it.day < today && it.end > now } + todayDuties)
            .sortedBy { it.start } else on(focus)
        return listOf(Day(focus, if (overnight != null) "OVERNIGHT / TODAY" else label(focus, true), primary),
            Day(second, label(second, false), on(second)))
    }
    fun range(duty: Duty, use24: Boolean): String {
        if (duty.allDay) return "ALL DAY"
        val pattern = DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a", Locale.ENGLISH)
        val start = Instant.ofEpochMilli(duty.start).atZone(zone)
        val end = Instant.ofEpochMilli(duty.end).atZone(zone)
        val extra = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate())
        return "${pattern.format(start)}–${pattern.format(end)}" + if (extra > 0) " (+$extra day${if (extra > 1) "s" else ""})" else ""
    }
    fun nextBoundary(duties: List<Duty>, now: Long): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (duties.map { it.end }.filter { it > now } + midnight).min()
    }
}
