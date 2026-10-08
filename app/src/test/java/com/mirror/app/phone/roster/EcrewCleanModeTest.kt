package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class EcrewCleanModeTest {
    @Test fun cleanIsDefaultAndSavedModesRoundTrip() {
        assertEquals(EcrewBrowserMode.CLEAN, EcrewBrowserMode.select(null))
        assertEquals(EcrewBrowserMode.CLEAN, EcrewBrowserMode.select("obsolete"))
        EcrewBrowserMode.entries.forEach { assertEquals(it, EcrewBrowserMode.select(it.name)) }
        assertTrue(EcrewBrowserMode.changed("NORMAL", EcrewBrowserMode.CLEAN))
        assertFalse(EcrewBrowserMode.changed("CLEAN", EcrewBrowserMode.CLEAN))
        assertFalse(EcrewBrowserMode.changed(null, EcrewBrowserMode.CLEAN))
    }
    @Test fun cleanAndPlainProhibitAutomaticScriptsAndCleanup() {
        for (mode in listOf(EcrewBrowserMode.CLEAN, EcrewBrowserMode.PLAIN)) {
            assertFalse(mode.automaticScripts); assertFalse(mode.automaticStorageCleanup)
        }
        assertTrue(EcrewBrowserMode.NORMAL.automaticScripts)
        assertEquals("Mozilla/5.0 (Linux; Android 15) Chrome/135 Mobile", EcrewBrowserIdentity.userAgent("Mozilla/5.0 (Linux; Android 15; wv) Version/4.0 Chrome/135 Mobile"))
    }
    @Test fun probeRequiresTapAndTrustedDashboard() {
        val url = "https://ecrew.cebupacificair.com/eCrew/Dashboard/"
        assertTrue(EcrewProbePolicy.allowed(true, url))
        assertFalse(EcrewProbePolicy.allowed(false, url))
        assertFalse(EcrewProbePolicy.allowed(true, "https://ecrew.cebupacificair.com/eCrew/Login"))
        assertFalse(EcrewProbePolicy.allowed(true, "https://other.test/eCrew/Dashboard"))
        assertFalse(EcrewProbePolicy.allowed(true, "https://ecrew.cebupacificair.com/eCrew/DashboardFake"))
    }
    @Test fun probeMasksRatherThanReturningShortOrFullTokens() {
        assertEquals("abcd…yz", EcrewProbePolicy.mask("abcdefghijklmnopqrstuvwx yz".replace(" ", "")))
        assertEquals("…", EcrewProbePolicy.mask("abc123"))
        assertEquals("[absent]", EcrewProbePolicy.mask(null))
        assertEquals("eCrewTabID", EcrewProbePolicy.name("eCrewTabID"))
        assertEquals(".AspNet.ApplicationCookie", EcrewProbePolicy.name(".AspNet.ApplicationCookie"))
        assertEquals("[name omitted]", EcrewProbePolicy.name("crew123456"))
        val raw = "<input value='synthetic-secret'><p>DOE 123456 BOOKINGABC</p>" + "Z".repeat(40)
        val sample = EcrewProbePolicy.body(raw)
        assertFalse(sample.contains("synthetic-secret")); assertFalse(sample.contains("DOE"))
        assertFalse(sample.contains("123456")); assertFalse(sample.contains("BOOKINGABC")); assertFalse(sample.contains("Z".repeat(40)))
        assertTrue(sample.length <= 300)
    }
    @Test fun probeJsonRetainsOnlyRequestedSafeFields() {
        val raw = JSONObject("""{"eCrewTabID":{"present":true,"length":40,"masked":"FULL_SECRET_TOKEN"},"sessionStorage":{"length":2,"keys":["eCrewTabID","123456"]},"verificationToken":{"count":1,"length":32,"value":"secret"},"xhr":{"status":400,"contentType":"text/html; charset=utf8","body":"DOE 123456"},"password":"never log"}""")
        val safe = EcrewProbe.redact(raw)
        assertFalse(safe.has("password")); assertFalse(safe.getJSONObject("verificationToken").has("value"))
        assertEquals("…", safe.getJSONObject("eCrewTabID").getString("masked"))
        assertEquals(400, safe.getJSONObject("xhr").getInt("status"))
        assertEquals("text/html", safe.getJSONObject("xhr").getString("contentType"))
        assertFalse(safe.toString().contains("123456")); assertFalse(safe.toString().contains("DOE"))
    }
    @Test fun nonRosterNeverReachesSaveAndRosterImportDoes() {
        val bytes = "%PDF-synthetic".toByteArray(); var writes = 0
        assertEquals(RosterImportRouting.Result.NOT_ROSTER, RosterImportRouting.route(bytes, { "Unrelated document" }, { writes++; true }))
        assertEquals(0, writes)
        assertEquals(RosterImportRouting.Result.IMPORTED, RosterImportRouting.route(bytes, { "Personal Crew Schedule Report" }, { writes++; true }))
        assertEquals(1, writes)
        assertEquals(RosterImportRouting.Result.FAILED, RosterImportRouting.route(bytes, { "Personal Crew Schedule Report" }, { false }))
        assertEquals(RosterImportRouting.Result.NOT_ROSTER, RosterImportRouting.route("not PDF".toByteArray(), { error("must not parse") }, { error("must not persist") }))
    }
}
