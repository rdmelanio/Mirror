package com.mirror.app.phone.roster
/** Only supplied dates replace trusted days. An unloaded day cannot erase an earlier duty. */
internal object RosterMerge {
 fun merge(old:Roster?,fresh:Roster):Roster {
  val supplied=fresh.duties.map { it.date }.toSet()
        val overlap = old != null && old.crewId == fresh.crewId && fresh.period.start <= old.period.end.plusDays(1) && old.period.start <= fresh.period.end.plusDays(1)
        return if (overlap) fresh.copy(period = Period(minOf(old!!.period.start, fresh.period.start), maxOf(old.period.end, fresh.period.end)),
                duties = (old.duties.filter { it.date !in supplied } + fresh.duties).sortedBy { it.reportInstant ?: it.date.atStartOfDay(AirportZones.zone("MNL")).toInstant() }, memos = old.memos.filterKeys { it !in supplied } + fresh.memos, legend = old.legend + fresh.legend) else fresh
 }
}
