package com.mirror.app.phone.roster
import java.time.LocalDate
/** Trusted ingestion boundary. Portal adapters must establish publication and complete coverage first. */
class ValidatedRoster private constructor(val roster:Roster,val source:String) {
 companion object {
  fun validate(roster:Roster,published:Boolean,coveredDates:Set<LocalDate>,source:String):ValidatedRoster {
   require(published) { "UNPUBLISHED_SOURCE" }
   require(source in setOf("published-storage","published-calendar","current-calendar","duty-details")) { "UNKNOWN_SOURCE" }
   require(roster.crewId.isNotBlank() && !roster.period.end.isBefore(roster.period.start) && roster.period.end<=roster.period.start.plusDays(62)) { "INVALID_IDENTITY_OR_PERIOD" }
   val expected=generateSequence(roster.period.start){it.plusDays(1)}.takeWhile {it<=roster.period.end}.toSet()
   require(coveredDates.containsAll(expected)) { "INCOMPLETE_COVERAGE" }
   require(expected.all { day -> roster.duties.any { d -> d.date==day || (d.reportLocal!=null && d.releaseLocal!=null && d.releaseLocal>d.reportLocal && day>=d.reportLocal.toLocalDate() && day<=d.releaseLocal.minusNanos(1).toLocalDate()) } }) { "MISSING_DUTIES" }
   require(roster.duties.map {it.key}.distinct().size==roster.duties.size) { "DUPLICATE_DUTY" }
   roster.duties.forEach { d ->
    require(d.date in roster.period.start.minusDays(7)..roster.period.end.plusDays(7)) { "OUTSIDE_PERIOD" }
    if(d.type==DutyType.OFF) require(d.code=="OFF") { "OFF_MUST_BE_EXPLICIT" }
    if(d.type in setOf(DutyType.FLIGHT,DutyType.STANDBY)) {
     require(d.reportLocal!=null && d.reportInstant!=null && d.releaseLocal!=null && d.releaseInstant!=null && !d.releaseEstimated) { "INCOMPLETE_DUTY_TIMES" }
     require(!d.releaseInstant!!.isBefore(d.reportInstant)) { "INVALID_DUTY_ORDER" }
    }
    if(d.type==DutyType.FLIGHT) require(d.legs.isNotEmpty()) { "MISSING_SECTORS" }
    d.legs.forEach { l -> require(l.depApt.matches(Regex("[A-Z]{3}")) && l.arrApt.matches(Regex("[A-Z]{3}")) && l.flightNo.isNotBlank()) { "INCOMPLETE_SECTOR" } }
   }
   return ValidatedRoster(roster,source)
  }
 }
}
