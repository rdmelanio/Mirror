package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class RosterMergeTest {
 @Test fun partialPeriodCannotEraseUnsuppliedDays() {
  val day=LocalDate.of(2026,11,1);val p=Period(day,day.plusDays(1))
  val a=Duty(day,DutyType.OFF,"OFF");val b=Duty(day.plusDays(1),DutyType.STANDBY,"HS")
  val old=Roster(p,"synthetic",Instant.EPOCH,listOf(a,b),mapOf(b.date to "keep"))
  val fresh=old.copy(duties=listOf(a.copy(code="RVL",type=DutyType.LEAVE)),memos=emptyMap())
  val merged=RosterMerge.merge(old,fresh)
  assertEquals(2,merged.duties.size);assertEquals(b,merged.duties.single {it.date==b.date});assertEquals("keep",merged.memos[b.date])
  assertEquals("RVL",merged.duties.single {it.date==a.date}.code)
 }
}
