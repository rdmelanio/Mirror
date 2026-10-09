package com.mirror.app.phone

import com.mirror.app.phone.roster.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Printed station-local values only; never convert leg clocks to the device zone. */
object ClockEcrewRoster {
    data class Line(val text: String, val small: Boolean = false)
    data class Day(val date: LocalDate, val label: String, val lines: List<Line>)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private fun stamp(value: LocalDateTime) = time.format(value)
    fun lines(duty: Duty): List<Line> = buildList {
        if (duty.code == "CHECK") add(Line("⚠ check eCrew"))
        else if (duty.legs.isNotEmpty()) {
            duty.reportLocal?.let { add(Line("RPT ${stamp(it)}")) }
            duty.legs.forEach { leg ->
                val dep = (if (leg.depKind == "S") "" else leg.depKind) + stamp(leg.depTime)
                val arr = (if (leg.arrKind == "S") "" else leg.arrKind) + stamp(leg.arrTime)
                add(Line("${if (leg.deadhead) "DHC " else ""}5J${leg.flightNo} ${leg.depApt} $dep→${leg.arrApt} $arr"))
            }
            duty.releaseLocal?.let { add(Line("REL ${stamp(it)}${if (duty.releaseEstimated) " est" else ""}")) }
        } else {
            add(Line(duty.code + (duty.reportLocal?.let { " ${stamp(it)}" } ?: "") + (duty.releaseLocal?.let { "–${stamp(it)}" } ?: "")))
        }
        val end = duty.releaseLocal ?: duty.legs.lastOrNull()?.arrTime
        if (end != null && end.toLocalDate() > duty.date) add(Line("→ +${java.time.temporal.ChronoUnit.DAYS.between(duty.date, end.toLocalDate())}", true))
    }
    fun days(roster: Roster?, now: Instant): List<Day> {
        val today = now.atZone(ClockRoster.zone).toLocalDate()
        val todays = roster?.duties.orEmpty().filter { it.date == today }
        val finished = todays.isNotEmpty() && todays.all { it.releaseInstant != null && it.releaseInstant <= now }
        val overnight = roster?.duties.orEmpty().filter { it.date < today && it.reportInstant != null && it.releaseInstant != null && it.releaseInstant > now }.minByOrNull { it.reportInstant!! }
        val focus = overnight?.date ?: if (finished) today.plusDays(1) else today
        return (0L..1L).map { offset ->
            val date = focus.plusDays(offset)
            val label = if (date < today) date.dayOfWeek.name else when (date) { today -> "TODAY"; today.plusDays(1) -> if (offset == 0L) "NEXT · TOMORROW" else "TOMORROW"; else -> date.dayOfWeek.name }
            val duties = roster?.duties.orEmpty().filter { it.date == date }.sortedBy { it.reportInstant ?: date.atStartOfDay(ClockRoster.zone).toInstant() }
            val content = if (roster == null) listOf(Line("Link eCrew in Roster Link")) else duties.flatMap(::lines).ifEmpty { listOf(Line("No roster entry")) }
            val memo = duties.any { it.memoFlag || it.memo != null } || roster?.memos?.containsKey(date) == true
            Day(date, label, content + if (memo) listOf(Line("✉ memo", true)) else emptyList())
        }
    }
    fun countdown(roster: Roster?, now: Instant): String? {
        val report = roster?.duties.orEmpty().mapNotNull { it.reportInstant }.filter { it > now }.minOrNull() ?: return null
        val minutes = Duration.between(now, report).toMinutes().coerceAtLeast(0)
        return "NEXT · T-${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }
    fun nextBoundary(roster: Roster?, now: Long): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(ClockRoster.zone).toLocalDate().plusDays(1).atStartOfDay(ClockRoster.zone).toInstant().toEpochMilli()
        return (roster?.duties.orEmpty().flatMap { listOfNotNull(it.reportInstant?.toEpochMilli(), it.releaseInstant?.toEpochMilli()) }.filter { it > now } + midnight).min()
    }
}
