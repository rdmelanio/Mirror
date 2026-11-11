package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class CameraEpochWatchdogTest {
    @Test fun freshEpochAllowsSlowFirstFrameAndRecoversRealStalls() {
        val w=CameraEpochWatchdog();w.start(0)
        assertFalse(w.stalled(20_000));assertTrue(w.stalled(20_001))
        w.frame(20_002);assertFalse(w.stalled(28_002));assertTrue(w.stalled(28_003))
        w.stop();assertFalse(w.stalled(100_000));w.frame(100_000)
        w.start(200_000);assertFalse(w.stalled(200_001));assertFalse(w.stalled(220_000));assertTrue(w.stalled(220_001))
    }
}
