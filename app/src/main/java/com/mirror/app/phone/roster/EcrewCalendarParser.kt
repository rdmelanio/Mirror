package com.mirror.app.phone.roster

import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter

/** Adapter for the observed eCrew CrewSchedule inline calendar schema. Never executes portal code. */
object EcrewCalendarParser {
 private val stamp=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
 private val sector=Regex("(?m)^\\s*(\\*?\\d+[A-Z]?)\\s*-\\s*([A-Z]{3})\\s*\\(([AE]?\\d{4}(?:⁺[⁰¹²³]|\\+\\d+)?)\\)\\s*-\\s*([A-Z]{3})\\s*\\(([AE]?\\d{4}(?:⁺[⁰¹²³]|\\+\\d+)?)\\)")
 fun parseHtml(html:String):ValidatedRoster {
  require(html.toByteArray(Charsets.UTF_8).size<=EcrewScheduleData.HTML_LIMIT) { "SOURCE_LIMIT" }
  fun literal(pattern:String):Any {
   val match=Regex(pattern).find(html) ?: error("MISSING_SCHEMA_FIELD")
   return JSONTokener(html.substring(match.range.last+1)).nextValue()
  }
  fun storage(key:String)=literal("localStorage\\[['\"]$key['\"]\\]\\s*=\\s*") as? String ?: error("INVALID_METADATA")
  val events=literal("\\bvar\\s+Events\\s*=\\s*") as? JSONArray ?: error("MISSING_EVENTS")
  val mode=Regex("times_in_selection\\s*=\\s*['\"]([123])['\"]").findAll(html).lastOrNull()?.groupValues?.get(1) ?: error("UNKNOWN_TIME_MODE")
  require(mode=="1") { "LOCAL_STATION_REQUIRED" }
  val crew=Regex("\\b[A-Z]{2}\\s*-\\s*(\\d{4,})").findAll(storage("CrewInformation").replace(Regex("<[^>]+>")," ")).toList()
  require(crew.size==1) { "AMBIGUOUS_CREW" }
  val start=LocalDate.parse(storage("PeriodStart"));val visibleEnd=LocalDate.parse(storage("PeriodEnd"));val publishedEnd=LocalDate.parse(storage("PublishEnd"))
  require(publishedEnd>=start) { "UNPUBLISHED_PERIOD" }
  return parseEvents(events,Period(start,minOf(visibleEnd,publishedEnd)),crew.single().groupValues[1])
 }
 private fun zone(airport:String):ZoneId {
  var known=true;val z=AirportZones.zone(airport) {known=false};require(known) {"UNKNOWN_STATION_ZONE"};return z
 }
 private fun instant(local:LocalDateTime,zone:ZoneId):Instant {
  val offsets=zone.rules.getValidOffsets(local)
  require(offsets.size==1) {"AMBIGUOUS_STATION_TIME"}
  return local.toInstant(offsets.single())
 }
 private fun time(raw:String,base:LocalDate):LocalDateTime {
  val cleaned=raw.replace("⁺⁰","+0").replace("⁺¹","+1").replace("⁺²","+2").replace("⁺³","+3")
  val m=Regex("[AE]?(\\d{2})(\\d{2})(?:\\+(\\d+))?").matchEntire(cleaned) ?: error("INVALID_SECTOR_TIME")
  val offset=m.groupValues[3].ifEmpty {"0"}.toLong();require(offset in 0..7) {"INVALID_DAY_OFFSET"}
  return base.plusDays(offset).atTime(m.groupValues[1].toInt(),m.groupValues[2].toInt())
 }
 internal fun parseEvents(events:JSONArray,period:Period,crew:String):ValidatedRoster {
  require(events.length() in 1..500 && !period.end.isBefore(period.start) && period.end<=period.start.plusDays(62)) {"INVALID_PERIOD"}
  val covered=mutableSetOf<LocalDate>();val duties=mutableListOf<Duty>()
  for(i in 0 until events.length()) {
   val e=events.getJSONObject(i)
   // A is the observed own/current roster row. Reject other crew/published overlays rather than mixing them.
   require(e.getString("resource")=="A") {"UNSUPPORTED_RESOURCE"}
   val from=LocalDateTime.parse(e.getString("start_date"),stamp);val to=LocalDateTime.parse(e.getString("end_date"),stamp)
   require(!to.isBefore(from) && to<=from.plusDays(7)) {"INVALID_EVENT_RANGE"}
   if(from.toLocalDate()>period.end || to.toLocalDate()<period.start) continue
   var day=from.toLocalDate();val last=if(to>from) to.minusNanos(1).toLocalDate() else day
   while(day<=last) {covered.add(day);day=day.plusDays(1)}
   val kind=e.getString("type");val text=e.getString("text").trim();val code=text.lineSequence().first().trim().split(Regex("\\s+")).first().take(64)
   val date=from.toLocalDate();val details=e.getString("details")
   val legs=if(kind=="Flight") sector.findAll(details).map { m ->
    val departure=time(m.groupValues[3],date);val arrival=time(m.groupValues[5],date)
    val dep=m.groupValues[2];val arr=m.groupValues[4]
    require(!instant(arrival,zone(arr)).isBefore(instant(departure,zone(dep)))) {"INVALID_SECTOR_ORDER"}
    val depKind=m.groupValues[3].first().toString().takeIf {it in listOf("A","E")} ?: "S"
    val arrKind=m.groupValues[5].first().toString().takeIf {it in listOf("A","E")} ?: "S"
    Leg(m.groupValues[1].removePrefix("*"),dep,arr,departure,arrival,depKind,null,m.groupValues[1].startsWith("*")||e.optBoolean("IsDeadhead"),depKind,arrKind)
   }.toList() else emptyList()
   val timed=kind in setOf("Flight","Standby")
   if(kind=="Flight") {
    require(legs.isNotEmpty()) {"MISSING_SECTORS"}
    val candidateLines=details.lineSequence().count {Regex("^\\s*\\*?\\d+[A-Z]?\\s*-\\s*[A-Z]{3}").containsMatchIn(it)}
    require(candidateLines==legs.size) {"INCOMPLETE_SECTORS"}
    require(legs.zipWithNext().all { (a,b)-> !instant(b.depTime,zone(b.depApt)).isBefore(instant(a.arrTime,zone(a.arrApt))) }) {"INVALID_SECTOR_SEQUENCE"}
   }
   if(timed) {
    val reportText=e.getString("report");val releaseText=e.getString("debrief")
    if(kind=="Standby" && reportText.isBlank() && releaseText.isBlank()) {
     val interval=Regex("(?m)^\\s*(\\d{4})-([0-9]{4})\\s*$").find(details) ?: error("MISSING_STANDBY_TIMES")
     require(interval.groupValues[1]==from.toLocalTime().format(DateTimeFormatter.ofPattern("HHmm")) && interval.groupValues[2]==to.toLocalTime().format(DateTimeFormatter.ofPattern("HHmm"))) {"CONTRADICTORY_STANDBY_TIMES"}
    } else require(reportText==from.toLocalTime().toString().take(5) && releaseText==to.toLocalTime().toString().take(5)) {"CONTRADICTORY_DUTY_TIMES"}
   }
   val reportZone=if(legs.isNotEmpty()) zone(legs.first().depApt) else if(timed) zone(e.getString("location").trim()) else null
   val releaseZone=if(legs.isNotEmpty()) zone(legs.last().arrApt) else reportZone
   val report=if(timed) instant(from,reportZone!!) else null
   val release=if(timed) instant(to,releaseZone!!) else null
   if(legs.isNotEmpty()) require(!report!!.isAfter(instant(legs.first().depTime,reportZone!!)) && !release!!.isBefore(instant(legs.last().arrTime,releaseZone!!))) {"INVALID_REPORT_RELEASE"}
   val type=when(kind) {"Flight"->DutyType.FLIGHT;"Standby"->DutyType.STANDBY;"Off"->DutyType.OFF;"Hotel","Default"->DutyType.OTHER;else->error("UNKNOWN_EVENT_TYPE")}
   duties+=Duty(date,type,code,from,report,to,release,false,legs,e.optString("delay").ifBlank {null},memoFlag=e.optBoolean("Memo"))
  }
  return ValidatedRoster.validate(Roster(period,crew,Instant.now(),duties),true,covered,"current-calendar")
 }
}
