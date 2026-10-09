package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewFramesTest {
    @Test fun realAimsViewerPortsAreTrustedButLoginIsNeverAccepted() {
        val f = EcrewFrames<Int>()
        assertTrue(f.hello(1, false, "/AIMS/CrewScheduleReport/PrintReport"))
        assertTrue(f.hello(2, false, "/AIMS/CrewScheduleReport/WebDocumentViewerInvoke"))
        assertFalse(f.hello(3, false, "/AIMS/Login"))
        assertFalse(f.hello(3, false, "/AIMS/AutoLogin"))
    }
    @Test fun helloLimitDisconnectAndOrigins() {
        val f = EcrewFrames<Int>()
        assertFalse(f.hello(0, true, "/eCrew/Login"))
        assertFalse(f.hello(0, true, "//evil.test/eCrew/"))
        assertFalse(f.hello(0, true, "/eCrew/Home?secret=123456"))
        repeat(8) { assertTrue(f.hello(it, it == 0, "/eCrew/Dashboard/HomeIndex")) }
        assertEquals(0, f.top()); assertFalse(f.hello(8, false, "/eCrew/Home"))
        f.disconnect(0); assertNull(f.top()); assertTrue(f.hello(8, false, "/eCrew/Home"))
        f.clear(); assertTrue(f.frames.isEmpty())
    }
    @Test fun firstNonWaitWinsAndWaitAllWaits() {
        val f = EcrewFrames<Int>(); f.hello(1, true, "/eCrew/Home"); f.hello(2, false, "/eCrew/Home")
        val first = f.begin(3)
        assertNull(f.result(1, 3, first, "wait")); assertEquals("schedule", f.result(2, 3, first, "schedule"))
        assertNull(f.result(1, 3, first, "printed"))
        val second = f.begin(4)
        assertNull(f.result(2, 3, first, "schedule")); assertNull(f.result(1, 4, second, "wait"))
        assertEquals("wait", f.result(2, 4, second, "wait"))
        val third = f.begin(4); assertNull(f.result(2, 4, second, "printed"))
        f.disconnect(2); assertEquals("wait", f.result(1, 4, third, "wait"))
    }
    @Test fun statusIsOrAcrossFrames() {
        val f = EcrewFrames<Int>(); f.hello(1, true, "/eCrew/Home"); f.hello(2, false, "/eCrew/Home")
        f.frames[2]!!.pending = true; f.frames[2]!!.linked = true; f.frames[2]!!.terminated = true
        assertTrue(f.pending); assertTrue(f.linked); assertTrue(f.terminated)
        f.disconnect(2); assertFalse(f.pending); assertFalse(f.linked); assertFalse(f.terminated)
    }
    @Test fun blankPopupBootstrapIsScopedToExports() {
        for (url in listOf("", "about:blank")) {
            assertFalse(EcrewPortPolicy.exportPage(url)); assertTrue(EcrewPortPolicy.exportPage(url, allowBlank = true))
        }
        assertFalse(EcrewPortPolicy.exportPage("https://other.test/Export", true))
        assertFalse(EcrewPortPolicy.exportPage("https://ecrew.cebupacificair.com/eCrew/Login", true))
        assertTrue(EcrewPortPolicy.exportPage("blob:https://ecrew.cebupacificair.com/export"))
        assertFalse(EcrewPortPolicy.exportPage("blob:https://other.test/export", true))
    }
    @Test fun snapshotRedactsAndBounds() {
        assertEquals("Crew …", EcrewSnapshot.text("Crew 12345678"))
        assertEquals(30, EcrewSnapshot.text("x".repeat(50)).length)
        assertEquals("/eCrew/…", EcrewSnapshot.path("/eCrew/123456?secret=password#x"))
        assertNull(EcrewSnapshot.icon("fa-calendar secret=value")); assertEquals("fa-calendar-alt", EcrewSnapshot.icon("fa-calendar-alt"))
    }
    @Test fun pendingAutoClearPreservesUnreadDiff() {
        val pending = RosterBannerState().update(emptyList(), true)
        assertTrue(pending.visible); assertFalse(pending.update(emptyList(), false).visible)
        val both = pending.update(listOf("Report moved"), true)
        assertTrue(both.update(emptyList(), false).visible)
        assertFalse(both.acknowledge(both.revision).visible)
        assertTrue(both.acknowledge(both.revision).pending)
        assertFalse(both.acknowledge(both.revision).update(emptyList(), false).pending)
    }
}

