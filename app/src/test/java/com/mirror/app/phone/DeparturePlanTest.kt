package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DeparturePlanTest {
    private fun at(time: String) = LocalDateTime.parse(time).atZone(ClockRoster.zone).toInstant().toEpochMilli()
    private val report = at("2026-10-07T09:25:00")
    private val settings = ClockSettings(departureEnabled = true, calendarId = 4)
    private fun duty(text: String, start: Long = report, id: Long = 1, allDay: Boolean = false, missing: Boolean = false) =
        ClockRoster.Duty(id, text, start, start + 10 * 3_600_000L, Instant.ofEpochMilli(start).atZone(ClockRoster.zone).toLocalDate(), allDay, missing)
    @Test fun defaultsProduceEightTwentyFiveCautionAndEightThirtyFiveWarning() {
        val alerts = DeparturePlan.alerts(listOf(duty("MNL–DVO–ZAM–DVO–MNL")), settings, at("2026-10-07T07:00:00"), emptyMap())
        assertEquals(2, alerts.size)
        assertEquals(at("2026-10-07T08:25:00"), alerts[0].at)
        assertEquals(DeparturePlan.Kind.CAUTION, alerts[0].kind)
        assertEquals(at("2026-10-07T08:35:00"), alerts[1].at)
        assertEquals(DeparturePlan.Kind.WARNING, alerts[1].kind)
    }
    @Test fun excludesOffAndHomeStandbyButIncludesAirportTrainingAndNewCodes() {
        for (text in listOf("OFF DAY", "off", "HS", "HSA")) assertFalse(text, DeparturePlan.eligible(duty(text), settings))
        for (text in listOf("AS", "SIM", "CRM", "NEWCODE", "MNL–CEB–MNL")) assertTrue(text, DeparturePlan.eligible(duty(text), settings))
        assertFalse(DeparturePlan.eligible(duty("SIM", allDay = true), settings))
        assertFalse(DeparturePlan.eligible(duty("NEWCODE"), settings.copy(excludedDutyCodes = "HS, HSA, newcode")))
    }
    @Test fun incompleteFlightsAreSkippedUnlessExplicitlyAllowed() {
        val d = duty("MNL–CEB–MNL", missing = true)
        assertFalse(DeparturePlan.eligible(d, settings))
        assertTrue(DeparturePlan.eligible(d, settings.copy(allowCalendarStartAlerts = true)))
    }
    @Test fun overdueChoosesOnlyWarningAndNothingSoundsAfterReporting() {
        val d = duty("AS")
        val now = at("2026-10-07T08:40:00")
        assertEquals(DeparturePlan.Kind.WARNING, DeparturePlan.overdue(DeparturePlan.alerts(listOf(d), settings, now, emptyMap()), now)?.kind)
        val early = at("2026-10-07T08:30:00")
        assertEquals(DeparturePlan.Kind.CAUTION, DeparturePlan.overdue(DeparturePlan.alerts(listOf(d), settings, early, emptyMap()), early)?.kind)
        assertTrue(DeparturePlan.alerts(listOf(d), settings, report, emptyMap()).isEmpty())
    }
    @Test fun acknowledgementSuppressesCautionButLeavesWarningAndPreventsReplaysAfterEdits() {
        val d = duty("SIM"); val now = report - 2 * 3_600_000
        val key = "4:1:2026-10-07"
        val cautioned = DeparturePlan.alerts(listOf(d), settings, now, mapOf(key to 1))
        assertEquals(listOf(DeparturePlan.Kind.WARNING), cautioned.map { it.kind })
        assertTrue(DeparturePlan.alerts(listOf(d), settings, now, mapOf(key to 2)).isEmpty())
        assertTrue(DeparturePlan.alerts(listOf(d.copy(start = report + 60_000)), settings, now, mapOf(key to 2)).isEmpty())
        // Another recurring occurrence or selected calendar remains independent.
        assertEquals(2, DeparturePlan.alerts(listOf(duty("SIM", report + 24 * 3_600_000)), settings, now, mapOf(key to 2)).size)
        assertEquals(2, DeparturePlan.alerts(listOf(d), settings.copy(calendarId = 5), now, mapOf(key to 2)).size)
    }
    @Test fun overnightReportingSchedulesOnThePreviousPhilippineDay() {
        val d = duty("MNL–CEB–MNL", at("2026-10-08T00:25:00"))
        val alerts = DeparturePlan.alerts(listOf(d), settings, at("2026-10-07T22:00:00"), emptyMap())
        assertEquals(at("2026-10-07T23:25:00"), alerts[0].at)
        assertEquals(at("2026-10-07T23:35:00"), alerts[1].at)
    }
    @Test fun cancelledDutyDisabledStagesAndOffsetsHaveNoStalePlan() {
        val now = report - 3 * 3_600_000
        assertTrue(DeparturePlan.alerts(emptyList(), settings, now, emptyMap()).isEmpty())
        assertTrue(DeparturePlan.alerts(listOf(duty("AS")), settings.copy(departureEnabled = false), now, emptyMap()).isEmpty())
        val warning = DeparturePlan.alerts(listOf(duty("AS")), settings.copy(cautionEnabled = false, warningMinutes = 30), now, emptyMap())
        assertEquals(1, warning.size); assertEquals(report - 30 * 60_000, warning.single().at)
        val caution = DeparturePlan.alerts(listOf(duty("AS")), settings.copy(warningEnabled = false), now, emptyMap())
        assertEquals(DeparturePlan.Kind.CAUTION, caution.single().kind)
    }
    @Test fun warningScreenHasTenMinuteLimitAndCautionEndsAtReporting() {
        val now = report - 50 * 60_000
        assertEquals(now + 10 * 60_000, DeparturePlan.expires(DeparturePlan.Kind.WARNING, now, report))
        assertEquals(report, DeparturePlan.expires(DeparturePlan.Kind.CAUTION, now, report))
    }
    @Test fun settingsPersistBothSoundsAndDurationsAndMigrateExistingClock() {
        val s = settings.copy(cautionMinutes = 90, warningMinutes = 45, warningSeconds = 17, cautionEnabled = false,
            warningSound = "content://audio/warning", cautionSound = "content://audio/chime", excludedDutyCodes = "HS,HSA,HOME", allowCalendarStartAlerts = true)
        assertEquals(s, ClockSettings.parse(s.json()))
        assertFalse(ClockSettings.parse("{\"schema\":4}").departureEnabled)
        val bad = ClockSettings.parse("{\"warningSeconds\":0,\"cautionMinutes\":9999,\"warningMinutes\":-1}")
        assertEquals(1, bad.warningSeconds); assertEquals(1440, bad.cautionMinutes); assertEquals(1, bad.warningMinutes)
    }
}
