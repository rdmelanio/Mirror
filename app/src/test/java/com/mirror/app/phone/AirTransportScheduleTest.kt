package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirTransportScheduleTest {
    @Test fun idleBackoffPreservesPendingAndNegotiationPriority() {
        assertEquals(80L,AirTransportSchedule.delay(true,true,false))
        assertEquals(0L,AirTransportSchedule.delay(true,true,true))
        assertEquals(20L,AirTransportSchedule.delay(false,true,false))
        assertEquals(500L,AirTransportSchedule.delay(false,false,false))
    }
}
