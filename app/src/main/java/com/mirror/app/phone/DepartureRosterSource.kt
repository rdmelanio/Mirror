package com.mirror.app.phone

import android.content.Context
import com.mirror.app.phone.roster.*

object DepartureSourcePolicy {
    enum class Source { CALENDAR, ECREW }
    @Suppress("UNUSED_PARAMETER")
    fun select(preference: String, linked: Boolean, hasRoster: Boolean, full: Boolean = true): Source = if (!full) Source.CALENDAR else when (preference) {
        "Calendar" -> Source.CALENDAR
        "eCrew" -> Source.ECREW
        else -> if (hasRoster) Source.ECREW else Source.CALENDAR
    }
}
object DepartureRosterSource {
    const val ECREW_ID = -2L
    fun select(c: Context, s: ClockSettings = ClockSettings.load(c)) = DepartureSourcePolicy.select(if (RosterStore.phone(c)) s.departureSource else "Calendar",
        RosterStore.phone(c) && RosterStore.prefs(c).getBoolean("linked", false), RosterStore.phone(c) && RosterStore.load(c) != null)
    fun snapshot(c: Context): ClockCalendar.Snapshot {
        val roster = RosterStore.load(c) ?: return ClockCalendar.Snapshot(calendarId = ECREW_ID, loading = false, error = "Link eCrew in Roster Link")
        val duties = roster.duties.filter { it.code != "CHECK" }.map { d ->
            val start = d.reportInstant?.toEpochMilli() ?: d.date.atStartOfDay(AirportZones.zone("MNL")).toInstant().toEpochMilli()
            val end = d.releaseInstant?.toEpochMilli() ?: start + if (d.reportInstant == null) 86_400_000 else 60_000
            val text = if (d.legs.isEmpty()) d.code else buildString {
                var previous = ""
                d.legs.forEach { l -> if (isEmpty()) append(l.depApt) else if (previous != l.depApt) append(" / ").append(l.depApt); append("–").append(l.arrApt); previous = l.arrApt }
            }
            val identity = "ecrew:${d.date}:${d.code}:${d.legs.firstOrNull()?.flightNo.orEmpty()}"
            ClockRoster.Duty(identity.hashCode().toLong(), text, start, maxOf(end, start + 1), d.date, d.reportInstant == null, sourceKey = identity)
        }
        return ClockCalendar.Snapshot(ECREW_ID, duties, RosterStore.prefs(c).getLong("lastSuccess", 0),
            roster.period.start.atStartOfDay(AirportZones.zone("MNL")).toInstant().toEpochMilli(), roster.period.end.plusDays(1).atStartOfDay(AirportZones.zone("MNL")).toInstant().toEpochMilli(), loading = false)
    }
}

