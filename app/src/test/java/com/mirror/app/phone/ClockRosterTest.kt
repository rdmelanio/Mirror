package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class ClockRosterTest {
    private fun time(text: String) = LocalDateTime.parse(text).atZone(ClockRoster.zone).toInstant().toEpochMilli()
    private fun event(title: String, description: String = "", start: String = "2026-10-07T09:25", end: String = "2026-10-07T19:30", id: Long = 1) =
        ClockRoster.Event(id, title, description, time(start), time(end), false)
    private val flight = """0973P
        |Reporting time : 0925
        |973 - MNL (1025) - DVO (E1213)
        |4793 - DVO (1300) - ZAM (E1406)
        |4794 - ZAM (1445) - DVO (1550)
        |970 - DVO (1725) - MNL (1930)
        |Debriefing time : 1930""".trimMargin()
    @Test fun screenshotRouteAndDutyTimes() {
        val d = ClockRoster.parse(event("0973P MNL-DVO-MNL", flight))
        assertEquals("MNL–DVO–ZAM–DVO–MNL", d.text)
        assertEquals("9:25 AM–7:30 PM", ClockRoster.range(d, false))
        assertFalse(d.calendarTimes)
    }
    @Test fun repeatedTurnsAreNotDeduplicatedAndDisconnectedLegsAreNotInvented() {
        val d = ClockRoster.parse(event("123P", """Reporting time: 0325
            |1 - MNL (0400) - CEB (0500)
            |2 - CEB (0530) - MNL (0630)
            |3 - MNL (0700) - CEB (0800)
            |4 - CEB (0830) - MNL (0925)
            |Debriefing time: 0925""".trimMargin(), "2026-10-07T03:25", "2026-10-07T09:25"))
        assertEquals("MNL–CEB–MNL–CEB–MNL", d.text)
        assertEquals("3:25 AM–9:25 AM", ClockRoster.range(d, false))
        val split = ClockRoster.parse(event("123P", "1 - MNL (0400) - CEB (0500)\n2 - DVO (0600) - MNL (0700)"))
        assertEquals("MNL–CEB / DVO–MNL", split.text)
        assertTrue(split.calendarTimes)
    }
    @Test fun standbyTrainingAndNewCodesKeepCalendarDutyTimes() {
        for (code in listOf("HS", "HSA", "AS", "SIM", "NEWCODE", "OFFICE")) {
            val d = ClockRoster.parse(event("$code MNL-MNL", start = "2026-10-08T12:00", end = "2026-10-08T18:00"))
            assertEquals(code, d.text); assertEquals("12:00–18:00", ClockRoster.range(d, true))
        }
    }
    @Test fun midnightDoesNotRemoveActiveDutyAndTomorrowRemainsVisible() {
        val d = ClockRoster.parse(event("123P", "Reporting time: 2200\nDebriefing time: 0230", "2026-10-07T22:00", "2026-10-08T02:30"))
        assertEquals("22:00–02:30 (+1 day)", ClockRoster.range(d, true))
        val midnight = ClockRoster.days(listOf(d), time("2026-10-08T00:01"))
        assertEquals(listOf(d), midnight[0].duties)
        assertEquals(LocalDate.parse("2026-10-09"), midnight[1].date)
        val ended = ClockRoster.days(listOf(d), time("2026-10-08T02:30"))
        assertTrue(ended[0].duties.isEmpty())
        assertEquals(time("2026-10-08T02:30"), ClockRoster.nextBoundary(listOf(d), time("2026-10-08T00:01")))
    }
    @Test fun reportBeforeMidnightWhenEventBeginsAfterMidnight() {
        val d = ClockRoster.parse(event("123P", "Reporting time: 2300\nDebriefing time: 0400", "2026-10-08T00:10", "2026-10-08T04:00"))
        assertEquals(time("2026-10-07T23:00"), d.start)
        assertEquals(LocalDate.parse("2026-10-07"), d.day)
    }
    @Test fun advanceOnlyAfterLastDutyAndDoNotSkipAnEmptyTomorrow() {
        val first = ClockRoster.parse(event("HS", start = "2026-10-07T08:00", end = "2026-10-07T10:00"))
        val last = ClockRoster.parse(event("SIM", start = "2026-10-07T15:00", end = "2026-10-07T17:00", id = 2))
        val later = ClockRoster.parse(event("AS", start = "2026-10-09T08:00", end = "2026-10-09T10:00", id = 3))
        val duties = listOf(first, last, later)
        val between = ClockRoster.days(duties, time("2026-10-07T12:00"))
        assertEquals(2, between[0].duties.size); assertTrue(between[1].duties.isEmpty())
        val end = ClockRoster.days(duties, time("2026-10-07T17:00"))
        assertEquals(LocalDate.parse("2026-10-08"), end[0].date); assertTrue(end[0].duties.isEmpty())
        assertEquals(listOf(later), end[1].duties)
    }
    @Test fun allDayCalendarDatesUseUtcEncodingButPhilippineBoundaries() {
        val event = ClockRoster.Event(1, "OFF Day", "", Instant.parse("2026-10-06T00:00:00Z").toEpochMilli(), Instant.parse("2026-10-08T00:00:00Z").toEpochMilli(), true)
        val d = ClockRoster.parse(event)
        assertEquals("OFF DAY", d.text); assertEquals("ALL DAY", ClockRoster.range(d, false))
        assertEquals(time("2026-10-06T00:00"), d.start); assertEquals(time("2026-10-08T00:00"), d.end)
        assertEquals(listOf(d), ClockRoster.days(listOf(d), time("2026-10-07T12:00"))[0].duties)
    }
    @Test fun missingOrInvalidDescriptionTimesFallBackWithoutInventingTimes() {
        for (description in listOf("", "Reporting time: 2500\nDebriefing time: 1960", "Reporting time: 0925")) {
            val d = ClockRoster.parse(event("0973P MNL-DVO-MNL", description))
            assertEquals(time("2026-10-07T09:25"), d.start); assertEquals(time("2026-10-07T19:30"), d.end)
            assertTrue(d.calendarTimes)
        }
    }
    @Test fun deviceTimeZoneDoesNotChangeRoster() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val d = ClockRoster.parse(event("0973P", flight))
            assertEquals("09:25–19:30", ClockRoster.range(d, true))
            assertEquals(LocalDate.parse("2026-10-07"), ClockRoster.days(listOf(d), time("2026-10-07T10:00"))[0].date)
        } finally { TimeZone.setDefault(before) }
    }
    @Test fun midnightRefreshWithNoDuties() {
        assertEquals(time("2026-10-08T00:00"), ClockRoster.nextBoundary(emptyList(), time("2026-10-07T23:59")))
    }
}
