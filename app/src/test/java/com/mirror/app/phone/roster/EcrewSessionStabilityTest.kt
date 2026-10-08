package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewSessionStabilityTest {
    @Test fun publishedUntilScheduleLabelMatches() {
        val label = "  My Schedule (published up until 31/10/2026)  "
        assertTrue(EcrewPageMatcher.schedule(label))
        assertTrue(EcrewPageMatcher.schedule("my schedule"))
        assertFalse(EcrewPageMatcher.schedule("Open My Schedule"))
        assertTrue(EcrewPageMatcher.signedIn(listOf(label), false, false, false))
        assertEquals("text", EcrewPageMatcher.scheduleTarget(listOf(label), true))
    }
    @Test fun sidebarAndAvatarFallbacks() {
        assertEquals("sidebar", EcrewPageMatcher.scheduleTarget(emptyList(), true))
        assertTrue(EcrewPageMatcher.signedIn(emptyList(), true, false, false))
        assertTrue(EcrewPageMatcher.signedIn(emptyList(), false, true, true))
        assertFalse(EcrewPageMatcher.signedIn(emptyList(), false, true, false))
        assertNull(EcrewPageMatcher.scheduleTarget(emptyList(), false))
    }
    @Test fun stopStartKeepsOneBrowserOneInitialLoadAndLease() {
        val life = EcrewBrowserLifetime(); val session = EcrewSessionCoordinator()
        val screen = session.openScreen(); val lease = session.acquireInteractive()!!
        var creations = 0; var loads = 0
        if (life.create()) creations++
        if (life.open()) loads++
        repeat(4) {
            life.start(); life.stop(); life.start()
            if (life.create()) creations++
            if (life.open()) loads++
            assertTrue(lease.ownsSession())
            assertNull(session.acquireWorker(1_000_000, 0) {})
        }
        assertEquals(1, creations); assertEquals(1, loads)
        assertTrue(life.destroy()); assertFalse(life.destroy()); assertFalse(life.open())
        lease.close(); screen.close()
        assertNotNull(session.acquireWorker(1_000_000, 0) {})
    }
    @Test fun alreadyLoadedEcrewDocumentNeverReloadedByOpen() {
        val life = EcrewBrowserLifetime(); life.create(); life.sawEcrewPage()
        assertFalse(life.open())
    }
    @Test fun logoutClearsOnlyAfterLoginAndOnlyOnce() {
        val order = EcrewLogoutOrder(); order.begin(1000)
        assertFalse(order.loginShown(false, 2000))
        assertEquals(EcrewLogoutOrder.State.WAITING, order.state)
        assertFalse(order.timeout(10_999))
        assertTrue(order.loginShown(true, 10_999))
        assertEquals(EcrewLogoutOrder.State.CLEARING, order.state)
        assertFalse(order.loginShown(true, 10_999)); order.complete()
        assertEquals(EcrewLogoutOrder.State.DONE, order.state)
    }
    @Test fun logoutTimeoutRetainsDataInsteadOfClearingLiveSession() {
        val order = EcrewLogoutOrder(); order.begin(1000)
        assertTrue(order.timeout(11_000))
        assertFalse(order.loginShown(true, 11_001))
        assertEquals(EcrewLogoutOrder.State.TIMED_OUT, order.state)
    }
    @Test fun terminationTextMatchesCaseAndEmbeddedMessage() {
        assertTrue(EcrewPageMatcher.terminated("Another active session is currently open under your account. This session has now been terminated."))
        assertTrue(EcrewPageMatcher.terminated("Alert: ANOTHER ACTIVE SESSION detected"))
        assertFalse(EcrewPageMatcher.terminated("My Schedule"))
    }
    @Test fun browserUaRemovesOnlyEmbeddedTokens() {
        val ua = "Mozilla/5.0 (Linux; Android 15; Phone Build/ABC; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/135.0.0.0 Mobile Safari/537.36"
        val clean = EcrewBrowserIdentity.userAgent(ua)
        assertFalse(clean.contains("; wv")); assertFalse(clean.contains("Version/4.0 "))
        assertTrue(clean.contains("Android 15")); assertTrue(clean.contains("Chrome/135.0.0.0 Mobile"))
        assertEquals(clean, EcrewBrowserIdentity.userAgent(clean))
    }
}
