package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewDiagnosticsTest {
    @Test fun pathKeepsRoutesButStripsQueriesAndIdentifiers() {
        assertEquals("ecrew.cebupacificair.com/eCrew/Dashboard/", EcrewLogRedaction.address("https://ecrew.cebupacificair.com/eCrew/Dashboard/?token=synthetic#secret"))
        assertEquals("/eCrew/api/*/Report", EcrewLogRedaction.path("/eCrew/api/123456/Report?secret=synthetic"))
        assertEquals("/api/*/file.pdf", EcrewLogRedaction.path("/api/" + "A".repeat(25) + "/file.pdf"))
        assertEquals("/api/" + "A".repeat(24), EcrewLogRedaction.path("/api/" + "A".repeat(24)))
        assertEquals("/api/*", EcrewLogRedaction.path("/api/id12ab34cd56"))
        assertEquals("/api/*", EcrewLogRedaction.path("/api/%31%32%33%34%35%36"))
        assertEquals("/api/*", EcrewLogRedaction.path("/api/%3Ftoken%3Dshort"))
        assertEquals("[invalid URL]", EcrewLogRedaction.address("http://[invalid"))
    }
    @Test fun consoleRedactsLongTokensQueriesAndPagePayloads() {
        val token = "A".repeat(40)
        val value = EcrewLogRedaction.console("Failed to load resource: https://example.test/api/123456?token=$token $token")
        assertFalse(value.contains(token)); assertFalse(value.contains("123456")); assertFalse(value.contains("?token"))
        assertTrue(value.contains("…")); assertTrue(value.length <= 200)
        assertEquals("[Login console message omitted]", EcrewLogRedaction.console("anything", login = true))
        assertEquals("[page console message omitted]", EcrewLogRedaction.console("synthetic crew payload"))
        assertFalse(EcrewLogRedaction.console("Uncaught Error: synthetic private payload").contains("private"))
        assertFalse(EcrewLogRedaction.console("Uncaught TypeError: Cannot read properties of 'synthetic-secret'").contains("synthetic-secret"))
        assertFalse(EcrewLogRedaction.console("Failed to load resource: password=synthetic-secret").contains("synthetic-secret"))
    }
    @Test fun requestWindowIsPerMethodAndExpiresAtSixtySeconds() {
        val window = EcrewRequestLogWindow()
        assertTrue(window.record("GET", "host/path", 1000))
        assertFalse(window.record("GET", "host/path", 60_999))
        assertTrue(window.record("POST", "host/path", 60_999))
        assertTrue(window.record("GET", "host/path", 61_000))
    }
    private fun nav(d: EcrewLoopDetector, path: String, at: Long) = d.navigation("ecrew.cebupacificair.com", path, at)
    @Test fun threeAlternationsDetectOnceAndIgnoreDuplicateCallbacks() {
        val d = EcrewLoopDetector()
        assertFalse(nav(d, "/eCrew/Dashboard/", 0))
        assertFalse(nav(d, "/eCrew/Dashboard", 100))
        assertFalse(nav(d, "/eCrew/", 5000))
        assertFalse(nav(d, "/eCrew/Dashboard/", 10_000))
        assertTrue(nav(d, "/eCrew", 15_000))
        assertFalse(nav(d, "/eCrew/Dashboard", 20_000))
    }
    @Test fun slowOrUnrelatedNavigationsAreNotLoops() {
        val slow = EcrewLoopDetector()
        repeat(8) { assertFalse(nav(slow, if (it % 2 == 0) "/eCrew" else "/eCrew/Dashboard", it * 31_000L)) }
        val unrelated = EcrewLoopDetector()
        assertFalse(nav(unrelated, "/eCrew", 0)); assertFalse(nav(unrelated, "/eCrew/Dashboard", 1000))
        assertFalse(nav(unrelated, "/eCrew/Login", 2000)); assertFalse(nav(unrelated, "/eCrew", 3000))
        assertFalse(unrelated.navigation("other.test", "/eCrew/Dashboard", 4000))
        assertFalse(nav(unrelated, "/eCrew/Dashboard", 5000))
    }
    @Test fun ineligibleLogoutTapIsDiscardedAndCannotReplay() {
        val gate = EcrewLogoutGate(); var clicks = 0
        assertFalse(gate.tap(false) { clicks++ })
        // Page loading, stop/start and a second instance have no path that can replay a tap.
        val second = EcrewLogoutGate()
        assertEquals(0, clicks)
        assertTrue(second.tap(true) { clicks++ })
        assertEquals(1, clicks)
        assertFalse(second.tap(false) { clicks++ }); assertEquals(1, clicks)
    }
}
