package com.mirror.app.phone

import com.mirror.app.phone.roster.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ClockEcrewRosterTest {
    private fun date(day: Int) = LocalDate.of(2026, 10, day)
    private fun instant(day: Int, hour: Int = 0) = date(day).atTime(hour, 0).atZone(ClockRoster.zone).toInstant()
    // The same printed flight, standby, leave, memo and deadhead examples as RosterTest.
    private val fixture = mapOf(
        1 to "Thu OFF",
        2 to "Fri 05:10 504 A07:17 MNL TUG A08:25 [32N] 505 A09:00 TUG MNL A10:10 [32N] Delay 01:07 10:40",
        5 to "Mon 09:25 973 A10:35 MNL DVO A12:30 [320] 4793 13:00 DVO ZAM A14:09 [320] 4794 A14:36 ZAM DVO A15:37 [320] 958 A16:55 *DVO MNL A19:12 Delay 00:10",
        9 to "Fri AS 18:00 →", 10 to "Sat ↓ 00:01",
        18 to "Sun RVL",
        26 to "Mon 20:50 2373 21:50 MNL BCD 23:15 [321] 2374 23:50 BCD → M",
        27 to "Tue ↓ MNL 01:15 [321] 01:45"
    ).mapKeys { date(it.key) }.mapValues { it.value.split(' ') }
    private fun roster() = Roster(Period(date(1), date(31)), "synthetic", Instant.EPOCH, RosterGrammar.parse(fixture))
    private fun text(day: Int) = ClockEcrewRoster.days(roster(), instant(day)).first().lines.map { it.text }
    @Test fun flightStationTimesAndKindsMatchThePrintout() {
        assertEquals(listOf("RPT 05:10", "5J504 MNL A07:17→TUG A08:25", "5J505 TUG A09:00→MNL A10:10", "REL 10:40"), text(2))
        val leg = roster().duties.first { it.date == date(2) }.legs.first().copy(depKind = "E", arrKind = "A")
        val duty = roster().duties.first { it.date == date(2) }.copy(legs = listOf(leg))
        assertTrue(ClockEcrewRoster.lines(duty).any { it.text == "5J504 MNL E07:17→TUG A08:25" })
    }
    @Test fun standbyOvernightIsKeptOnItsReportDate() {
        assertEquals(listOf("AS 18:00–00:01", "→ +1"), text(9))
        val beforeRelease = ClockEcrewRoster.days(roster(), instant(10))
        assertEquals(date(9), beforeRelease.first().date); assertEquals("FRIDAY", beforeRelease.first().label)
        assertFalse(beforeRelease[1].lines.any { it.text.startsWith("AS ") })
    }
    @Test fun offLeaveMemoAndEstimatedDeadheadRelease() {
        assertEquals(listOf("OFF"), text(1)); assertEquals(listOf("RVL"), text(18))
        assertTrue(text(5).contains("DHC 5J958 DVO A16:55→MNL A19:12")); assertTrue(text(5).contains("REL 19:42 est"))
        assertEquals(listOf("→ +1", "✉ memo"), text(26).takeLast(2))
        val memo = roster().copy(memos = mapOf(date(1) to "private memo"))
        assertEquals("✉ memo", ClockEcrewRoster.days(memo, instant(1)).first().lines.last().text)
    }
    @Test fun missingRosterCountdownAndBoundaries() {
        assertEquals("Link eCrew in Roster Link", ClockEcrewRoster.days(null, instant(1)).first().lines.single().text)
        val r = roster(); assertEquals("NEXT · T-5:10", ClockEcrewRoster.countdown(r, instant(2)))
        assertEquals(instant(2).plusSeconds(5 * 3600 + 10 * 60).toEpochMilli(), ClockEcrewRoster.nextBoundary(r, instant(2).toEpochMilli()))
        assertEquals(instant(2).toEpochMilli(), ClockEcrewRoster.nextBoundary(r, instant(1).toEpochMilli()))
    }
    @Test fun sharedSourceDefaultsExplicitChoiceAndLiteOnlyCalendar() {
        assertEquals(DepartureSourcePolicy.Source.ECREW, DepartureSourcePolicy.select("Auto", false, true))
        assertEquals(DepartureSourcePolicy.Source.CALENDAR, DepartureSourcePolicy.select("Auto", true, false))
        assertEquals(DepartureSourcePolicy.Source.CALENDAR, DepartureSourcePolicy.select("Calendar", true, true))
        assertEquals(DepartureSourcePolicy.Source.ECREW, DepartureSourcePolicy.select("eCrew", false, false))
        for (choice in listOf("Auto", "eCrew", "Calendar")) assertEquals(DepartureSourcePolicy.Source.CALENDAR, DepartureSourcePolicy.select(choice, true, true, full = false))
        val s = ClockSettings().copy(departureSource = "eCrew")
        assertEquals("eCrew", ClockSettings.parse(s.json().toString()).departureSource)
    }
}
