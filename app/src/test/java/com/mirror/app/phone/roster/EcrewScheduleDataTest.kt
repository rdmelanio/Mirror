package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewScheduleDataTest {
    @Test fun capturesOnlyEndpointMetadataAndKeyNames() {
        val item = EcrewScheduleData.decode("/eCrew/DutyDetails", 200, """{"duties":[{"report":"PRIVATE_TIME","tail":"PRIVATE_TAIL"}],"crew":"PRIVATE_CREW"}""")!!
        assertEquals(listOf("crew", "duties"), item.keys.sorted())
        assertEquals(listOf("report", "tail"), item.arrayKeys.sorted())
        assertFalse(item.keys.toString().contains("PRIVATE")); assertFalse(item.arrayKeys.toString().contains("PRIVATE"))
        assertTrue(item.value.toString().contains("PRIVATE_CREW")) // Values stay only in private raw capture.
    }
    @Test fun topLevelArrayAndBounds() {
        assertEquals(listOf("legs"), EcrewScheduleData.decode("/eCrew/Details", 200, """[{"legs":[]}]""")!!.arrayKeys)
        assertNull(EcrewScheduleData.decode("/eCrew/Details", 200, "x".repeat(EcrewScheduleData.LIMIT)))
        assertNull(EcrewScheduleData.decode("/eCrew/Details", 200, "{} trailing"))
        assertNull(EcrewScheduleData.decode("/eCrew/Login", 200, "{}"))
        assertNull(EcrewScheduleData.decode("//other.test/eCrew/Details", 200, "{}"))
        assertNull(EcrewScheduleData.decode("/eCrew/Details?crew=123456", 200, "{}"))
        assertNull(EcrewScheduleData.decode("/eCrew/Details", 999, "{}"))
    }
}
