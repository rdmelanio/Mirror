package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class AirContinuousTest {
    private class Rig(val step:Long,val calibration:Boolean=false) {
        var time=0L;var claims=0
        var confidence=.65f;var sensitivity=50;var jitter=false
        val sent=mutableListOf<AirGestures.Command>()
        val intended=mutableListOf<AirGestures.Command>()
        val session=AirControlSession({claims++},{sent.add(it)})
        fun frame(name:String="Open_Palm",x:Float=.5f,y:Float=.5f,size:Float=.1f,score:Float=.8f,empty:Boolean=false) {
            time+=step+if(jitter && time%3==0L) 120 else 0
            session.update(time,if(empty) emptyList() else listOf(AirOwnership.Hand(x,y,size,name,score)),true,calibration,step,confidence,sensitivity)
            session.accepted?.let { intended.add(it) }
        }
        fun hold(ms:Long,name:String="Open_Palm",x:Float=.5f,y:Float=.5f,size:Float=.1f) { repeat((ms/step).toInt()+1) { frame(name,x,y,size) } }
        fun activate() { hold(2500);assertTrue(session.gestures.armed);assertTrue(intended.isEmpty()) }
        fun swipe(dx:Float,dy:Float) {
            hold(800)
            // An intermediate sample follows observed hand movement, including at 2 FPS.
            frame(x=.5f+dx*.5f,y=.5f+dy*.5f)
            frame(x=.5f+dx,y=.5f+dy)
            hold(700,x=.5f+dx,y=.5f+dy)
            frame();hold(800) // return motion must never become the opposite command
        }
    }
    @Test fun fullNavigationSequenceHasOneClaimAtFastAndSofaInferenceRates() {
        for(step in listOf(83L,333L,480L)) {
            val r=Rig(step);r.jitter=step==333L;r.activate()
            r.swipe(.22f,0f);r.swipe(.22f,0f);r.swipe(0f,.22f)
            r.hold(1100,"Closed_Fist",size=.035f)
            r.hold(800);r.swipe(-.22f,0f)
            r.hold(1100,"Victory",size=.07f)
            r.hold(800);r.swipe(0f,-.22f)
            assertEquals("$step ms: ${r.intended}",listOf(AirGestures.Command.RIGHT,AirGestures.Command.RIGHT,AirGestures.Command.DOWN,AirGestures.Command.OK,AirGestures.Command.LEFT,AirGestures.Command.BACK,AirGestures.Command.UP),r.sent)
            assertEquals(1,r.claims);assertTrue(r.session.gestures.armed)
        }
    }
    @Test fun calibrationRunsSameSessionWithoutAnyTransmissions() {
        val r=Rig(400,true);r.activate();r.swipe(.22f,0f);r.hold(1100,"Closed_Fist")
        assertEquals(listOf(AirGestures.Command.RIGHT,AirGestures.Command.OK),r.intended)
        assertTrue(r.sent.isEmpty());assertEquals(0,r.claims)
    }
    @Test fun briefLandmarkAndClassificationGapsRecoverWithoutClaiming() {
        val r=Rig(400);r.activate();r.frame(empty=true);r.frame(empty=true)
        assertTrue(r.session.gestures.armed);assertTrue(r.session.status.contains("temporarily lost"))
        r.frame(score=.3f);assertTrue(r.session.status.contains("recovered"))
        r.swipe(.22f,0f);assertEquals(listOf(AirGestures.Command.RIGHT),r.sent);assertEquals(1,r.claims)
        repeat(6) { r.frame(empty=true) };assertFalse(r.session.gestures.armed)
    }
    @Test fun staticHeldReleaseAndHomeWorkWithoutAnotherActivation() {
        val r=Rig(400);r.activate()
        for(name in listOf("Closed_Fist","Victory","Thumb_Up")) { r.hold(2400,name);r.hold(900,"None") }
        r.hold(1200,"Closed_Fist")
        assertEquals(listOf(AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME,AirGestures.Command.OK),r.sent)
        assertEquals(1,r.claims)
    }
    @Test fun irregularTimingStationaryNoiseAndInactivityRemainSafe() {
        val r=Rig(333);r.activate()
        repeat(25) { i ->
            r.time+=if(i%3==0) 210 else 0
            r.frame(x=.5f+if(i%2==0) .009f else -.009f)
        }
        assertTrue(r.sent.isEmpty());assertTrue(r.session.gestures.armed)
        while(r.time<34_000) r.frame("None")
        assertFalse(r.session.gestures.armed)
    }
    @Test fun ambiguousCrossingAndOtherPersonNeverAcquireExistingSession() {
        val r=Rig(400);r.activate()
        fun h(x:Float)=AirOwnership.Hand(x,.5f,.1f,"Open_Palm",.9f)
        r.session.update(r.time+400,listOf(h(.48f),h(.52f)),true,false,400,.65f,50)
        assertTrue(r.session.status.contains("ambiguous"));assertTrue(r.sent.isEmpty())
        r.session.update(r.time+800,listOf(h(.8f)),true,false,400,.65f,50)
        assertFalse(r.session.gestures.armed);assertEquals(1,r.claims)
    }
    @Test fun confidenceAndSensitivityStillControlClaimAndDisplacement() {
        val r=Rig(333);r.confidence=.95f;r.hold(3000);assertFalse(r.session.gestures.armed)
        r.confidence=.65f;r.activate();r.sensitivity=0;r.swipe(.16f,0f);assertTrue(r.sent.isEmpty())
        r.sensitivity=100;r.swipe(.16f,0f);assertEquals(listOf(AirGestures.Command.RIGHT),r.sent)
    }

    @Test fun firstSwipeImmediatelyAfterClaimUsesClaimAnchorWithoutShapeChange() {
        for(step in listOf(333L,480L)) {
            val r=Rig(step)
            repeat(12) { if(!r.session.gestures.armed) r.frame() }
            assertTrue(r.session.gestures.armed);assertTrue(r.sent.isEmpty())
            r.frame(x=.72f)
            assertEquals(listOf(AirGestures.Command.RIGHT),r.sent);assertEquals(1,r.claims)
        }
    }

}
