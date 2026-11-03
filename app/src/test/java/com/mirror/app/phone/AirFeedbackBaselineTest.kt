package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AirFeedbackBaselineTest {
    @Test fun feedbackRetainsOrderedCommandsRatherThanReplacingEarlierCommand() {
        val e=AirFeedbackEvents { 1000 };e.post("SENT_UP");e.post("SENT_RIGHT")
        val body=JSONObject(e.poll(0,0))
        assertEquals("SENT_UP",body.getString("event"))
    }
    @Test fun authoritativeSnapshotIsSeparateFromDiscreteEvents() {
        val e=AirFeedbackEvents { 1000 };val body=JSONObject(e.poll(-1,0))
        assertTrue(body.has("snapshot"))
    }
}
