package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirDiagnostics218Test {
    @Test fun geometryIsNotDisplayedAsProbabilityAndFreshnessDropsAreVisible() {
        val d=AirDiagnostics { 1000 }
        d.record("Active","Owned","Open_Palm",1f,null,"Connected",10.0,20.0,"Normal",geometricEvidence=true)
        d.command("OBSERVATION_EXPIRED",AirGestures.Command.OK,1100)
        d.command("CLOCK_GAP",AirGestures.Command.UP,0)
        val output=d.text()
        assertTrue(output.contains("geometry validated"));assertFalse(output.contains("100%"))
        assertTrue(output.contains("OBSERVATION_EXPIRED"));assertTrue(output.contains("CLOCK_GAP"))
    }
    @Test fun oldCameraCallbackCannotBecomeNewEpochsFirstFrame() {
        val w=CameraEpochWatchdog();w.start(0,1);w.stop();w.start(100,2)
        w.frame(20_000,1);assertTrue(w.stalled(20_101))
        w.frame(20_102,2);assertFalse(w.stalled(20_103))
    }
}
