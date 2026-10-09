package com.mirror.app.phone.roster

import com.mirror.app.phone.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RosterReviewTest {
    @Test fun oneWorkerOwnerAtATime() {
        val lock = EcrewSessionCoordinator()
        val first = lock.acquireWorker(1_000_000, 0) {}!!
        assertNull(lock.acquireWorker(1_000_000, 0) {})
        first.close(); assertNotNull(lock.acquireWorker(1_000_000, 0) {})
    }
    @Test fun workerYieldsBeforeActivityGetsOwnership() {
        val lock = EcrewSessionCoordinator(); var worker: EcrewSessionCoordinator.Lease? = null; var destroyed = false
        worker = lock.acquireWorker(1_000_000, 0) { destroyed = true; worker!!.close() }
        val screen = lock.openScreen()
        assertTrue(destroyed); assertFalse(worker!!.ownsSession())
        val interactive = lock.acquireInteractive()!!
        assertNull(lock.acquireWorker(1_000_000, 0) {})
        interactive.close(); assertNull(lock.acquireWorker(1_000_000, 0) {}) // onStart→onStop reservation remains.
        screen.close(); assertNotNull(lock.acquireWorker(1_000_000, 0) {})
    }
    @Test fun parallelWorkersCannotBothAcquire() {
        val lock = EcrewSessionCoordinator(); val start = CountDownLatch(1); val attempted = CountDownLatch(8)
        val count = AtomicInteger(); val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) { executor.submit { start.await(); if (lock.acquireWorker(1_000_000, 0) {} != null) count.incrementAndGet(); attempted.countDown() } }
            start.countDown(); assertTrue(attempted.await(5, TimeUnit.SECONDS)); assertEquals(1, count.get())
        } finally { executor.shutdownNow() }
    }
    @Test fun periodicDelayAndInteractiveQuietWindow() {
        listOf(15,30,60).forEach { assertEquals(it, EcrewRefreshPolicy.initialDelayMinutes(it)) }
        assertFalse(EcrewRefreshPolicy.allowed(1_000_000 + 179_999, 1_000_000))
        assertTrue(EcrewRefreshPolicy.allowed(1_000_000 + 180_000, 1_000_000))
        assertFalse(EcrewRefreshPolicy.allowed(999_999, 1_000_000))
        val lock = EcrewSessionCoordinator(); assertNull(lock.acquireWorker(1_010_000, 1_000_000) {})
    }
    private fun date(day: Int) = LocalDate.of(2026, 10, day)
    @Test fun unreadableDutyResyncsAtNextColumn() {
        val unreadable = mutableListOf<LocalDate>()
        val days = mapOf(date(1) to "Thu OFF".split(' '), date(2) to "Fri 05:10 504 A07:17 MNL ??? A08:25 [32N] 10:40".split(' '), date(3) to "Sat AS 18:00 23:00".split(' '))
        val parsed = RosterGrammar.parse(days, unreadable = { unreadable += it })
        assertEquals(listOf(date(2)), unreadable)
        assertEquals(listOf("OFF", "CHECK", "AS"), parsed.map { it.code })
        assertNull(parsed[1].reportInstant); assertEquals(date(3).atTime(18, 0), parsed[2].reportLocal)
        assertTrue(RosterAlarmPlan.plan(parsed, listOf(RosterAlarm(1)), { true }).none { it.duty.code == "CHECK" })
    }
    @Test fun unfamiliarCodeRemainsAValidDuty() {
        val parsed = RosterGrammar.parse(mapOf(date(2) to listOf("ZZZ", "13:00", "14:00"), date(3) to listOf("OFF")))
        assertEquals("ZZZ", parsed.first().code); assertEquals(DutyType.OTHER, parsed.first().type); assertNotNull(parsed.first().reportInstant)
    }
    @Test fun strayMarkerDoesNotConsumeNextDay() {
        val parsed = RosterGrammar.parse(mapOf(date(1) to listOf("Thu", "↓"), date(2) to listOf("Fri", "OFF")))
        assertEquals(listOf("CHECK", "OFF"), parsed.map { it.code })
    }
    @Test(expected = IllegalArgumentException::class) fun tooManyUnreadableDaysRejectCapture() {
        RosterGrammar.parse((1..4).associate { date(it) to listOf("???") })
    }
    @Test fun alarmSourceDefaultsAndExplicitChoice() {
        assertEquals(DepartureSourcePolicy.Source.ECREW, DepartureSourcePolicy.select("Auto", true, true))
        assertEquals(DepartureSourcePolicy.Source.ECREW, DepartureSourcePolicy.select("Auto", false, true))
        assertEquals(DepartureSourcePolicy.Source.CALENDAR, DepartureSourcePolicy.select("Auto", true, false))
        assertEquals(DepartureSourcePolicy.Source.CALENDAR, DepartureSourcePolicy.select("Calendar", true, true))
        assertEquals(DepartureSourcePolicy.Source.ECREW, DepartureSourcePolicy.select("eCrew", false, true))
    }
    @Test fun sourceChangeDoesNotReplaySameStage() {
        val d = ClockRoster.Duty(123, "MNL–TAG–MNL", 20_000_000, 30_000_000, date(8), false)
        val ecrew = d.copy(id = 456, sourceKey = "ecrew:2026-10-08:FLIGHT:621")
        assertEquals(DeparturePlan.deliveryAlias(d), DeparturePlan.deliveryAlias(ecrew.copy(day = d.day.plusDays(1))))
        val settings = ClockSettings(departureEnabled = true, calendarId = 5)
        val first = DeparturePlan.alerts(listOf(d), settings, 0, emptyMap())
        assertEquals(2, first.size)
        val afterCaution = mapOf(first.first { it.kind == DeparturePlan.Kind.CAUTION }.occurrence to 1, DeparturePlan.deliveryAlias(d) to 1)
        val fromEcrew = DeparturePlan.alerts(listOf(ecrew, ecrew), settings.copy(calendarId = -2), 0, afterCaution)
        assertEquals(listOf(DeparturePlan.Kind.WARNING), fromEcrew.map { it.kind })
        assertTrue(DeparturePlan.alerts(listOf(d), settings, 0, mapOf(DeparturePlan.deliveryAlias(ecrew) to 2)).isEmpty())
    }
    @Test fun bannerAcknowledgementAndNewChanges() {
        val first = RosterBannerState().update(listOf("08/10: report moved"), false)
        assertTrue(first.visible)
        val acknowledged = first.acknowledge(first.revision); assertFalse(acknowledged.visible)
        val newer = acknowledged.update(listOf("09/10: OFF → AS"), false)
        assertTrue(newer.visible); assertTrue(newer.acknowledge(first.revision).visible)
        assertFalse(newer.acknowledge(newer.revision).visible)
    }
    @Test fun pendingChangeBannerDoesNotReappearEveryPoll() {
        val pending = RosterBannerState().update(emptyList(), true)
        val seen = pending.acknowledge(pending.revision)
        assertFalse(seen.update(emptyList(), true).visible)
        assertTrue(seen.update(emptyList(), false).update(emptyList(), true).visible)
    }
}

