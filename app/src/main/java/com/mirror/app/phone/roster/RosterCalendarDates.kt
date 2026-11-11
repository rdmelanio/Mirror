package com.mirror.app.phone.roster
import java.time.LocalDate
import java.time.format.DateTimeFormatter
/** Calendar spillover is separate from the declared publication period. Never infer a year blindly. */
internal object RosterCalendarDates {
    fun resolve(period:Period,header:String):LocalDate {
        require(header.matches(Regex("[0-9]{2}/[0-9]{2}"))) { "INVALID_DATE_LAYOUT" }
        val format=DateTimeFormatter.ofPattern("dd/MM")
        val candidates=generateSequence(period.start.minusDays(7)) { it.plusDays(1) }
            .takeWhile { it<=period.end.plusDays(7) }.filter { it.format(format)==header }.toList()
        require(candidates.size==1) { "INVALID_DATE_LAYOUT" }
        return candidates.single()
    }
}
