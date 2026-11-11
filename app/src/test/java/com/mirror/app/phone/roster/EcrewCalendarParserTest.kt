package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
import org.json.*
import java.time.*
class EcrewCalendarParserTest {
 private fun off(date:String)=JSONObject().put("resource","A").put("start_date","$date 00:00:00").put("end_date","$date 23:59:00").put("type","Off").put("text","OFF").put("details","")
 @Test fun explicitOffAndCoverageAreRequired() {
  val date=LocalDate.of(2026,11,1);val p=Period(date,date)
  assertEquals(DutyType.OFF,EcrewCalendarParser.parseEvents(JSONArray().put(off(date.toString())),p,"synthetic").roster.duties.single().type)
  try {EcrewCalendarParser.parseEvents(JSONArray().put(off(date.toString())),Period(date,date.plusDays(1)),"synthetic");fail()}catch (_:IllegalArgumentException){}
 }
 @Test fun fourSectorActualScheduledAndOvernightTimesAreRetained() {
  val e=off("2026-11-01").put("type","Flight").put("text","DUTY").put("start_date","2026-11-01 20:00:00").put("end_date","2026-11-02 07:00:00").put("report","20:00").put("debrief","07:00")
   .put("details","101 - MNL (A2100) - CEB (A2200)\n102 - CEB (2300) - MNL (0000⁺¹)\n103 - MNL (0100⁺¹) - CEB (0200⁺¹)\n104 - CEB (0400⁺¹) - MNL (0600⁺¹)")
  val r=EcrewCalendarParser.parseEvents(JSONArray().put(e),Period(LocalDate.of(2026,11,1),LocalDate.of(2026,11,2)),"synthetic").roster
  assertEquals(4,r.duties.single().legs.size);assertEquals("A",r.duties.single().legs.first().depKind)
  assertEquals(LocalDate.of(2026,11,2),r.duties.single().legs.last().arrTime.toLocalDate())
 }
 @Test fun standbyIntervalMustBeExplicitWhenReportFieldsAreEmpty() {
  val day=LocalDate.of(2026,11,1)
  val e=off(day.toString()).put("type","Standby").put("text","HS").put("start_date","2026-11-01 09:00:00").put("end_date","2026-11-01 17:00:00").put("report","").put("debrief","").put("location","MNL").put("details","Home Standby\n0900-1700")
  assertNotNull(EcrewCalendarParser.parseEvents(JSONArray().put(e),Period(day,day),"synthetic").roster.duties.single().reportInstant)
  e.put("details","Home Standby")
  try {EcrewCalendarParser.parseEvents(JSONArray().put(e),Period(day,day),"synthetic");fail()}catch (_:IllegalStateException){}
 }
 @Test fun noSuccessfulCompletionUntilCommitAndOldResultsCannotCompleteStoppedSession() {
  val outcomes=mutableListOf<Boolean>()
  val a=EcrewAutomation({0L},{_,_->},{},{ok,_->outcomes.add(ok)})
  a.startStructured();a.scheduleDataSaved();assertTrue(outcomes.isEmpty());a.structuredCommitted();assertEquals(listOf(true),outcomes)
  a.startStructured();a.stop();a.structuredCommitted();assertEquals(listOf(true,false),outcomes)
 }
 @Test fun ambiguousDstReportTimeIsRejectedInsteadOfGuessingAnOffset() {
  val day=LocalDate.of(2026,4,5)
  val e=off(day.toString()).put("type","Flight").put("text","DUTY").put("start_date","2026-04-05 02:30:00").put("end_date","2026-04-05 05:00:00").put("report","02:30").put("debrief","05:00").put("details","101 - SYD (0330) - SYD (0430)")
  try {EcrewCalendarParser.parseEvents(JSONArray().put(e),Period(day,day),"synthetic");fail()}catch(e:IllegalArgumentException){assertEquals("AMBIGUOUS_STATION_TIME",e.message)}
 }
 @Test fun capturedHtmlOrSyntheticEquivalentParsesWithoutExecutingScript() {
  val path=System.getenv("MIRROR_PRIVATE_CALENDAR_HTML")
  val html=if(path!=null) java.io.File(path).readText() else """<script>var times_in_selection='1';var Events=[${off("2026-11-01")}];localStorage['CrewInformation']="<table><td>FA - 000000</td></table>";localStorage['PeriodStart']="2026-11-01";localStorage['PeriodEnd']="2026-11-01";localStorage['PublishEnd']="2026-11-01";</script>"""
  try {assertTrue(EcrewCalendarParser.parseHtml(html).roster.duties.isNotEmpty())}catch(e:Exception){fail("Calendar validation category: "+(e.message?.takeIf {it.matches(Regex("[A-Z_]+"))} ?: e.javaClass.simpleName))}
 }
}
