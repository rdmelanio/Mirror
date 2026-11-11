package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirConfidence218Test {
    private fun hand(geometry:Boolean, palm:Boolean=true)=AirOwnership.Hand(.3f,.4f,.12f,if(palm) "Open_Palm" else "None",if(palm) 1f else 0f,geometricEvidence=geometry)
    @Test fun allThresholdsRequireSustainedPalmForBothEvidenceTypes() {
        for(threshold in listOf(50,65,85,90,95)) for(geometry in listOf(false,true)) {
            val o=AirOwnership().apply { claimDurationMs=1000;claimSamples=5;minimumConfidence=threshold/100f }
            repeat(10) { assertFalse(o.update(it*200L,listOf(hand(geometry,false))).claimed) }
            o.reset()
            for(t in 0L..800L step 200) assertFalse(o.update(t,listOf(hand(geometry))).claimed)
            assertTrue("$threshold $geometry",o.update(1000,listOf(hand(geometry))).claimed)
        }
    }
    @Test fun geometryHighThresholdWaitsThroughDropoutAndAmbiguousPalmsNeverClaim() {
        val o=AirOwnership().apply { claimDurationMs=1000;claimSamples=5;minimumConfidence=.95f }
        for(t in 0L..1000L step 200) assertFalse(o.update(t,listOf(hand(true,t!=600L))).claimed)
        var claimed=false
        for(t in 1200L..4000L step 200) { if(o.update(t,listOf(hand(true))).claimed) { claimed=true;break } }
        assertTrue(claimed)
        o.reset()
        for(t in 0L..3000L step 200) assertFalse(o.update(t,listOf(hand(true),hand(true).copy(x=.7f))).claimed)
    }
    @Test fun compatibilityProbabilityStillGatesPalm() {
        val o=AirOwnership().apply { claimDurationMs=1000;minimumConfidence=.95f }
        for(t in 0L..3000L step 200) assertFalse(o.update(t,listOf(hand(false).copy(confidence=.85f))).claimed)
    }
    @Test fun resetClearsJoystickActivityAndRequiresFreshCenter() {
        val j=AirJoystick();val h=AirOwnership.Hand(.4f,.4f,.1f,"POINT_UP",1f)
        j.update(0,h,50);j.update(150,h,50)
        assertEquals(AirGestures.Command.RIGHT,j.update(300,h.copy(x=.7f),50));assertEquals(300L,j.activityAt)
        j.reset();assertEquals(-1L,j.activityAt);assertFalse(j.hasAnchor);assertFalse(j.pending);assertEquals("Joystick neutral",j.reason)
        assertNull(j.update(2000,h.copy(shoulder=AirPointing.Point(.2f,.2f)),50))
        assertNull(j.update(2150,h.copy(shoulder=AirPointing.Point(.2f,.2f)),50))
        assertNull(j.update(2300,h.copy(shoulder=AirPointing.Point(.2f,.2f)),50))
    }
}
