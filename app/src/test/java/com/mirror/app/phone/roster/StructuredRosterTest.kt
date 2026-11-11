package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class StructuredRosterTest {
 @Test fun requiresPublicationAndCompleteExplicitCoverage() {
  val day=LocalDate.of(2026,11,1);val r=Roster(Period(day,day),"synthetic",Instant.EPOCH,listOf(Duty(day,DutyType.OFF,"OFF")))
  assertNotNull(ValidatedRoster.validate(r,true,setOf(day),"published-storage"))
  for(action in listOf<()->Unit>({ValidatedRoster.validate(r,false,setOf(day),"published-storage")},{ValidatedRoster.validate(r,true,emptySet(),"published-storage")},{ValidatedRoster.validate(r.copy(duties=emptyList()),true,setOf(day),"published-storage")})) {
   try {action();fail("incomplete data accepted")} catch (_:IllegalArgumentException) {}
  }
 }
 @Test fun directJsonCommitPreservesBothPdfArchives() {
  val dir=java.nio.file.Files.createTempDirectory("structured").toFile()
  try {
   java.io.File(dir,"latest.pdf").writeText("latest");java.io.File(dir,"previous.pdf").writeText("previous")
   RosterTransaction(dir).commit(null,"new json")
   assertEquals("latest",java.io.File(dir,"latest.pdf").readText());assertEquals("previous",java.io.File(dir,"previous.pdf").readText());assertEquals("new json",java.io.File(dir,"roster.json").readText())
  } finally {dir.deleteRecursively()}
 }
 @Test fun rawCaptureTimeoutCannotReportSuccessfulRoster() {
  var time=0L;val results=mutableListOf<Boolean>();val commands=mutableListOf<String>()
  val a=EcrewAutomation({time},{c,_->commands.add(c)},{},{s,_->results.add(s)})
  a.startStructured();a.tick();a.response(a.request,"schedule");a.scheduleDataSaved();a.tick()
  assertFalse(commands.contains("print"));assertTrue(commands.contains("calendarSnapshot"))
  time=60001;a.tick();assertEquals(listOf(false),results)
 }
}
