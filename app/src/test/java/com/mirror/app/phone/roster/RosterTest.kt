package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RosterTest {
    private fun date(day: Int) = LocalDate.of(2026, 10, day)
    private val fixture = mapOf(
        1 to "Thu OFF",
        2 to "Fri 05:10 504 A07:17 MNL TUG A08:25 [32N] 505 A09:00 TUG MNL A10:10 [32N] Delay 01:07 10:40",
        5 to "Mon 09:25 973 A10:35 MNL DVO A12:30 [320] 4793 13:00 DVO ZAM A14:09 [320] 4794 A14:36 ZAM DVO A15:37 [320] 958 A16:55 *DVO MNL A19:12 Delay 00:10",
        8 to "Thu 17:15 621 18:15 MNL TAG 19:50 [32N] 622 20:25 TAG MNL 22:05 [32N] 22:35",
        9 to "Fri AS 18:00 →",
        10 to "Sat ↓ 00:01 17:15 621 18:15 MNL TAG 19:50 [32Q] 622 20:25 TAG MNL 22:05 [32Q] 22:35",
        14 to "Wed 05:05 587 06:05 MNL CEB 07:40 [320] 4306 09:00 *CEB CRK 10:30",
        15 to "Thu 04:30 1082 05:30 CRK DVO 07:30 [32N] 962 08:50 *DVO MNL 10:50",
        18 to "Sun RVL",
        26 to "Mon 20:50 2373 21:50 MNL BCD 23:15 [321] 2374 23:50 BCD → M",
        27 to "Tue ↓ MNL 01:15 [321] 01:45",
        30 to "Fri 03:10 383 04:10 MNL CGY 05:55 [32N] 384 06:35 CGY MNL 08:20 [32N] 899 08:50 MNL MPH 10:00 [32N] 900 10:45 MPH MNL 12:00 [32N] 12:30"
    ).mapKeys { date(it.key) }.mapValues { it.value.split(' ') }
    private fun duties() = RosterGrammar.parse(fixture)
    private fun duty(day: Int) = duties().first { it.date == date(day) }
    @Test fun allFixtureDutiesParse() { assertEquals(11, duties().size); assertEquals(DutyType.OFF, duty(1).type); assertNull(duty(1).reportInstant); assertEquals(DutyType.LEAVE, duty(18).type) }
    @Test fun actualTimesAndDelay() {
        val d = duty(2); assertEquals("05:10", d.reportLocal!!.toLocalTime().toString()); assertEquals(2, d.legs.size)
        assertTrue(d.legs.all { it.timeKind == "A" }); assertEquals("01:07", d.delay); assertEquals("10:40", d.releaseLocal!!.toLocalTime().toString())
    }
    @Test fun deadheadReleases() {
        val d = duty(5); assertEquals(4, d.legs.size); assertTrue(d.legs.last().deadhead); assertNull(d.legs.last().aircraft)
        assertTrue(d.releaseEstimated); assertEquals(date(5).atTime(19, 42), d.releaseLocal)
        val layover = duty(14); assertEquals(2, layover.legs.size); assertEquals("CEB", layover.legs.last().depApt); assertEquals("CRK", layover.legs.last().arrApt)
        assertTrue(layover.releaseEstimated); assertEquals(date(14).atTime(11, 0), layover.releaseLocal)
        assertEquals(date(15).atTime(11, 20), duty(15).releaseLocal)
    }
    @Test fun standbyContinuationAndSecondDuty() {
        val d = duty(9); assertEquals("AS", d.code); assertEquals(date(9).atTime(18, 0), d.reportLocal); assertEquals(date(10).atTime(0, 1), d.releaseLocal)
        assertEquals(date(10).atTime(17, 15), duty(10).reportLocal); assertEquals("32Q", duty(10).legs.first().aircraft)
    }
    @Test fun midLegContinuationAndMemo() {
        val d = duty(26); val l = d.legs.last(); assertEquals("2374", l.flightNo); assertEquals(date(26).atTime(23, 50), l.depTime)
        assertEquals(date(27).atTime(1, 15), l.arrTime); assertEquals("MNL", l.arrApt); assertEquals("321", l.aircraft)
        assertEquals(date(27).atTime(1, 45), d.releaseLocal); assertTrue(d.memoFlag)
        assertFalse(duties().any { it.date == date(27) })
    }
    @Test fun alarmTimingAndRollback() {
        val plans = RosterAlarmPlan.plan(duties(), listOf(RosterAlarm(1)), { it.type == DutyType.FLIGHT || it.code == "AS" })
        assertEquals(date(8).atTime(15, 55).atZone(AirportZones.zone("MNL")).toInstant(), plans.first { it.duty.date == date(8) }.at)
        // 03:10 - 01:20 is 01:50 on the SAME day; original requested rollback expectation was arithmetic error.
        assertEquals(date(30).atTime(1, 50).atZone(AirportZones.zone("MNL")).toInstant(), plans.first { it.duty.date == date(30) }.at)
        val early = duty(30).copy(reportInstant = date(30).atTime(0, 30).atZone(AirportZones.zone("MNL")).toInstant())
        assertEquals(date(29).atTime(23, 10).atZone(AirportZones.zone("MNL")).toInstant(), RosterAlarmPlan.plan(listOf(early), listOf(RosterAlarm(1)), { true }).single().at)
    }
    @Test fun zeroWidthSpace() { assertEquals("05:10", RosterText.clean(" \uFEFF05:10\u200B ")); assertEquals(duties(), RosterGrammar.parse(fixture.mapValues { (_, values) -> values.map { "$it\u200B" } })) }
    @Test fun syntheticColumnBinning() {
        val headers = listOf(Word("01/10", 6f, 14f, 20f), Word("02/10", 32f, 40f, 20f))
        val words = listOf(Word("OFF", 8f, 12f, 50f), Word("05:10\u200B", 32f, 40f, 45f), Word("504", 35f, 37f, 60f), Word("ignored", 90f, 95f, 55f), Word("footer", 8f, 12f, 101f))
        assertEquals(listOf(listOf("OFF"), listOf("05:10", "504")), RosterText.bin(words, headers, 30f, 100f))
    }
    private fun roster(d: List<Duty>) = Roster(Period(date(1), date(31)), "000000", Instant.EPOCH, d)
    @Test fun diffSummaries() {
        val old = roster(listOf(duty(8))); val empty = old.copy(duties = listOf(Duty(date(8), DutyType.OFF, "OFF")))
        assertEquals(listOf("08/10: 2 legs removed"), RosterDiff.summaries(old, empty))
        assertEquals(listOf("08/10: OFF → 5J621 RPT 17:15"), RosterDiff.summaries(empty, old))
        assertTrue(RosterDiff.summaries(old, old.copy(generatedAt = Instant.now())).isEmpty())
    }
    @Test fun alarmReschedulingAndSkipping() {
        val original = RosterAlarmPlan.plan(listOf(duty(8)), listOf(RosterAlarm(1)), { true }).single()
        val movedDuty = duty(8).copy(reportInstant = duty(8).reportInstant!!.plusSeconds(3600))
        val updated = RosterAlarmPlan.plan(listOf(movedDuty), listOf(RosterAlarm(1)), { true })
        assertEquals(1, RosterAlarmPlan.moved(mapOf(original.key to original.at), updated).size)
        assertTrue(RosterAlarmPlan.plan(listOf(movedDuty), listOf(RosterAlarm(1)), { true }, setOf(original.key)).isEmpty())
        assertTrue(RosterAlarmPlan.plan(listOf(duty(8)), listOf(RosterAlarm(1, enabled = false)), { true }).isEmpty())
    }
    @Test fun jsonRoundtripAndZones() {
        val r = roster(duties()); assertEquals(r, RosterJson.decode(RosterJson.encode(r)))
        assertEquals("Australia/Sydney", AirportZones.zone("SYD").id); assertEquals("Asia/Manila", AirportZones.zone("MNL").id)
        var warning = false; assertEquals("Asia/Manila", AirportZones.zone("ZZZ") { warning = true }.id); assertTrue(warning)
    }
    @Test(expected = IllegalArgumentException::class) fun incompleteLegRejected() { RosterGrammar.parse(mapOf(date(26) to "20:50 2373 21:50 MNL →".split(' '))) }
}
