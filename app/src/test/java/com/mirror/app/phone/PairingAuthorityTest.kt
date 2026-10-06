package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingAuthorityTest {
    @Test fun blockedNotificationsHaveIndependentOneMinuteWindowsPerIp() {
        var time = 0L
        val security = PairingAuthority(elapsed = { time })
        assertTrue(security.shouldNotifyBlocked("192.168.1.2"))
        assertFalse(security.shouldNotifyBlocked("192.168.1.2"))
        assertTrue(security.shouldNotifyBlocked("192.168.1.3"))
        time = 59_999
        assertFalse(security.shouldNotifyBlocked("192.168.1.2"))
        time = 60_000
        assertTrue(security.shouldNotifyBlocked("192.168.1.2"))
    }
    @Test fun codeRequiresVisibleScreenExpiresAndLocksAfterFiveFailures() {
        var time = 1000L
        val security = PairingAuthority(elapsed = { time })
        assertEquals(403, security.pair("123456", "id", "TV").status)
        val code = security.showCode()!!
        assertTrue(code.matches(Regex("[0-9]{6}")))
        time += 120_000
        assertEquals(403, security.pair(code, "id", "TV").status)
        val next = security.showCode()!!
        val wrong = if (next == "000000") "000001" else "000000"
        repeat(4) { assertEquals(403, security.pair(wrong, "id", "TV").status) }
        assertEquals(429, security.pair(wrong, "id", "TV").status)
        assertEquals(429, security.pair(next, "id", "TV").status)
        assertNull(security.showCode())
        time += 60_000
        val regenerated = security.showCode()!!
        assertEquals(200, security.pair(regenerated, "id", "TV").status)
        security.hideCode()
        assertEquals(403, security.pair(regenerated, "another", "TV 2").status)
    }
    @Test fun onlyHashesPersistAndRevocationSurvivesRestart() {
        var saved = ""
        var wall = 100L
        val security = PairingAuthority(persist = { saved = it }, now = { wall })
        val token = security.pair(security.showCode()!!, "id", "TCL TV").token!!
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        assertFalse(saved.contains(token))
        assertEquals("TCL TV", security.authenticate(token)!!.name)
        assertNull(security.authenticate("x".repeat(43)))
        wall += 60_000; security.authenticate(token)
        assertEquals(wall, security.list().single().lastSeen)
        val restored = PairingAuthority(saved)
        assertEquals(security.cameraId, restored.cameraId)
        assertNotNull(restored.authenticate(token))
        var revoked: String? = null
        restored.onRevoked = { revoked = it }
        restored.remove(restored.list().single().hash)
        assertNotNull(revoked); assertNull(restored.authenticate(token))
    }
    @Test fun browserPasswordIsOffByDefaultHashedAndCanBeDisabled() {
        var saved = ""
        val security = PairingAuthority(persist = { saved = it })
        val auth = "Basic " + Base64.getEncoder().encodeToString("mirror:browser-secret".toByteArray())
        assertFalse(security.browserAuth(auth))
        security.setBrowserPassword("browser-secret")
        assertFalse(saved.contains("browser-secret"))
        assertTrue(security.browserAuth(auth))
        assertFalse(security.browserAuth("Basic invalid"))
        security.setBrowserPassword(null); assertFalse(security.browserAuth(auth))
    }
}
