package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewScheduleDataTest {
    @Test fun localStorageMetadataNeverIncludesValuesAndIsBounded() {
        val entries = org.json.JSONObject().put("CrewInformation", """[{"crew":"PRIVATE_CREW","duty":"PRIVATE_DUTY"}]""")
            .put("PeriodStart", "PRIVATE_DATE").put("Memos", """{"memo":"PRIVATE_MEMO"}""")
        val metadata = EcrewScheduleData.storageMetadata(entries)!!
        val crew = metadata.first { it.startsWith("key=CrewInformation ") }
        assertTrue(crew.contains("arrayLength=1")); assertTrue(crew.contains("type=array"))
        assertTrue(metadata.joinToString().contains("memo"))
        assertFalse(metadata.joinToString().contains("PRIVATE"))
        assertNull(EcrewScheduleData.storageMetadata(org.json.JSONObject().put("huge", "x".repeat(EcrewScheduleData.STORAGE_LIMIT))))
        assertNull(EcrewScheduleData.storageMetadata(org.json.JSONObject().put("invalid", 42)))
    }
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

