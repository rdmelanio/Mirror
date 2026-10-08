package com.mirror.app.phone

import java.util.Locale

/** Pure calendar-to-alarm policy. All inputs are absolute instants parsed in Philippine time. */
object DeparturePlan {
    enum class Kind(val level: Int) { CAUTION(1), WARNING(2) }
    data class Alert(val occurrence: String, val kind: Kind, val at: Long, val duty: ClockRoster.Duty) {
        val token get() = "$occurrence:${kind.name}:$at"
    }
    fun eligible(d: ClockRoster.Duty, s: ClockSettings): Boolean {
        val code = d.text.trim().uppercase(Locale.ENGLISH).substringBefore(' ')
        val excluded = s.excludedDutyCodes.split(',', ' ', '\n').map { it.trim().uppercase(Locale.ENGLISH) }.filter { it.isNotEmpty() }
        return !d.allDay && !Regex("^OFF(?:\\b|$)").containsMatchIn(code) && code !in excluded &&
            (!d.calendarTimes || s.allowCalendarStartAlerts)
    }
    fun occurrence(d: ClockRoster.Duty, s: ClockSettings) = d.sourceKey ?: "${s.calendarId}:${d.id}:${d.day}"
    fun deliveryAlias(d: ClockRoster.Duty) = "report:${java.time.Instant.ofEpochMilli(d.start).atZone(ClockRoster.zone).toLocalDate()}:${d.start}"
    fun alerts(duties: List<ClockRoster.Duty>, s: ClockSettings, now: Long, delivered: Map<String, Int>): List<Alert> {
        if (!s.departureEnabled) return emptyList()
        return duties.filter { it.start > now && eligible(it, s) }.flatMap { d ->
            val occurrence = occurrence(d, s)
            val level = maxOf(delivered[occurrence] ?: 0, delivered[deliveryAlias(d)] ?: 0)
            buildList {
                if (s.cautionEnabled && level < 1) add(Alert(occurrence, Kind.CAUTION, d.start - s.cautionMinutes * 60_000L, d))
                if (s.warningEnabled && level < 2) add(Alert(occurrence, Kind.WARNING, d.start - s.warningMinutes * 60_000L, d))
            }
        }.distinctBy { it.token }.sortedBy { it.at }
    }
    /** One most urgent overdue alert; never replay both sounds after a late refresh. */
    fun overdue(alerts: List<Alert>, now: Long): Alert? = alerts.filter { it.at <= now }
        .sortedWith(compareByDescending<Alert> { it.kind.level }.thenBy { it.duty.start }).firstOrNull()
    fun next(alerts: List<Alert>, kind: Kind, now: Long): Alert? = alerts.firstOrNull { it.kind == kind && it.at > now }
    fun expires(kind: Kind, started: Long, reporting: Long): Long =
        if (kind == Kind.WARNING) started + 10 * 60_000L else reporting
}
