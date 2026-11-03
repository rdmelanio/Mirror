package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test

class AirHouseholdTest {
    private fun h(name:String="Open_Palm",x:Float=.25f,score:Float=.78f,size:Float=.1f)=AirOwnership.Hand(x,.5f,size,name,score)
    private class Rig(val timeout:Long=60_000,val calibration:Boolean=false) {
        var now=0L;var claims=0
        val sent=mutableListOf<AirGestures.Command>()
        val s=AirControlSession({claims++},{sent.add(it)},household=true)
        fun frame(hands:List<AirOwnership.Hand>,connected:Boolean=true,dt:Long=350) {
            now+=dt;s.update(now,hands,connected,calibration,dt,.65f,50,false,400,timeout)
        }
        fun claim() { for(i in 0..5) frame(listOf(AirOwnership.Hand(.25f,.5f,.1f,if(i==2) "None" else "Open_Palm",if(i==2) .3f else .8f))) ;assertTrue(s.sessionActive) }
    }
    @Test fun accidentalPalmNeverClaimsAndSimultaneousClaimantsNeverChooseArbitrarily() {
        val r=Rig();r.frame(listOf(h()));repeat(8) { r.frame(emptyList()) };assertFalse(r.s.sessionActive)
        repeat(10) { r.frame(listOf(h(x=.25f),h(x=.8f))) };assertFalse(r.s.sessionActive)
    }
    @Test fun longAbsenceAndRecoverableInferenceFailureKeepSessionButBlockCommands() {
        val r=Rig();r.claim();r.s.pauseRecognition(r.now)
        repeat(12) { r.frame(emptyList()) };assertTrue(r.s.sessionActive);assertTrue(r.sent.isEmpty())
        r.frame(listOf(h("Thumb_Up",size=.07f)));assertTrue(r.sent.isEmpty())
        repeat(4) { r.frame(listOf(h("Thumb_Up",size=.08f))) }
        assertEquals(listOf(AirGestures.Command.OK),r.sent);assertEquals(1,r.claims)
    }
    @Test fun transferRequiresDeliberatePalmAndNewOwnerHasPriority() {
        val r=Rig();r.claim();r.frame(emptyList())
        repeat(4) { r.frame(listOf(h("POINT_DOWN",x=.8f))) };assertTrue(r.sent.isEmpty());assertEquals(1,r.claims)
        repeat(5) { r.frame(listOf(h(x=.8f))) };assertEquals("TRANSFERRED",r.s.event);val event=r.s.eventSerial
        for(pose in listOf("POINT_UP","POINT_RIGHT","Thumb_Up","Thumb_Down")) repeat(4) { r.frame(listOf(h(pose,x=.8f),h(x=.25f))) }
        assertEquals(event,r.s.eventSerial);assertEquals(1,r.claims)
        assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK),r.sent)
    }
    @Test fun backgroundWavingCannotStealFromInteractingOwnerAndAmbiguityCannotAuthorize() {
        val r=Rig();r.claim()
        for(pose in listOf("POINT_UP","POINT_RIGHT","Thumb_Up")) repeat(4) { r.frame(listOf(h(pose),h(x=.85f))) }
        assertEquals(1,r.claims);assertEquals("ACTIVE",r.s.event)
        val count=r.sent.size
        repeat(8) { r.frame(listOf(h("POINT_LEFT",x=.24f),h("POINT_DOWN",x=.28f))) }
        assertEquals(count,r.sent.size);assertTrue(r.s.sessionActive);assertEquals("AMBIGUOUS",r.s.trackingState)
    }
    @Test fun connectionLossRetainsAuthorityAndNeverReplaysHeldKeys() {
        val r=Rig();r.claim();repeat(5) { r.frame(listOf(h("POINT_UP"))) };assertEquals(1,r.sent.size)
        repeat(5) { r.frame(listOf(h("POINT_UP")),connected=false) };assertTrue(r.s.sessionActive);assertEquals(1,r.sent.size)
        repeat(6) { r.frame(listOf(h("POINT_UP"))) };assertEquals(1,r.sent.size)
        repeat(4) { r.frame(listOf(h("Closed_Fist"))) };repeat(5) { r.frame(listOf(h("POINT_UP"))) }
        assertEquals(2,r.sent.size);assertEquals(1,r.claims)
    }
    @Test fun configurableTimeoutExpiresOnceAndTrackingLossIsNotExpiry() {
        for(timeout in listOf(30_000L,60_000L,120_000L,300_000L)) {
            val r=Rig(timeout);r.claim();val at=r.s.lastActivityAt;val initial=r.s.eventSerial
            while(r.now<at+timeout-500) r.frame(emptyList(),dt=400)
            assertTrue(r.s.sessionActive);assertEquals(initial,r.s.eventSerial)
            r.s.heartbeat(at+timeout);assertFalse(r.s.sessionActive);assertEquals("EXPIRED",r.s.event)
            val once=r.s.eventSerial;repeat(5) { r.s.heartbeat(at+timeout+it*1000) };assertEquals(once,r.s.eventSerial)
        }
    }
    @Test fun staticDropoutsReleaseAndEntireSequenceWorkWithoutExtraClaim() {
        val r=Rig();r.claim()
        fun hold(pose:String,ms:Long) { val end=r.now+ms;var i=0
            while(r.now<end) { r.frame(listOf(h(if(i==1) "None" else pose,score=if(i==1) .2f else .76f)),dt=if(i%2==0) 330 else 470);i++ }
        }
        hold("POINT_UP",1700);hold("Closed_Fist",1000);hold("POINT_UP",1700);hold("POINT_RIGHT",1700)
        hold("Thumb_Up",1700);hold("Thumb_Down",1700);hold("Victory",2000)
        assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.UP,AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),r.sent)
        assertEquals(1,r.claims)
    }
    @Test fun calibrationCannotTransmitOrTriggerClaimCallback() {
        val r=Rig(calibration=true);r.claim();repeat(6) { r.frame(listOf(h("Thumb_Up"))) }
        assertTrue(r.s.sessionActive);assertTrue(r.sent.isEmpty());assertEquals(0,r.claims)
    }
    @Test fun apparentSizeAndPositionOutlierPausesWithoutErasingAuthority() {
        val r=Rig();r.claim();r.frame(listOf(h("None",x=.45f,size=.6f)))
        assertTrue(r.s.sessionActive);assertTrue(r.sent.isEmpty())
        repeat(3) { r.frame(listOf(h("Closed_Fist",x=.27f,size=.07f))) }
        assertTrue(r.s.trackingOwned);assertEquals(1,r.claims)
    }
    @Test fun pendingDwellNearDeadlineCompletesButStationaryHandDoesNotKeepAuthorityForever() {
        val r=Rig();r.claim();val start=r.s.lastActivityAt
        while(r.now<start+59_000) r.frame(listOf(h("Closed_Fist")),dt=400)
        repeat(4) { r.frame(listOf(h("Victory")),dt=400) }
        assertTrue(r.s.sessionActive);assertEquals(listOf(AirGestures.Command.HOME),r.sent)
        repeat(160) { r.frame(listOf(h("Victory")),dt=400) }
        assertFalse(r.s.sessionActive);assertEquals(1,r.sent.size)
    }
    @Test fun intentionalTransportBlockedAttemptsCountAsActivityButAreNeverReplayed() {
        val r=Rig();r.claim()
        for(pose in listOf("POINT_UP","POINT_RIGHT","Thumb_Up")) repeat(5) { r.frame(listOf(h(pose)),connected=false) }
        assertTrue(r.s.lastActivityAt>3000);assertTrue(r.sent.isEmpty())
        repeat(6) { r.frame(listOf(h("Thumb_Up"))) };assertTrue(r.sent.isEmpty())
        repeat(4) { r.frame(listOf(h("Closed_Fist"))) };repeat(5) { r.frame(listOf(h("Thumb_Up"))) }
        assertEquals(listOf(AirGestures.Command.OK),r.sent)
    }

    @Test fun palmsHeldAcrossTransferCannotPingPongOwnership() {
        val r=Rig();r.claim()
        repeat(12) { r.frame(listOf(h(x=.25f),h(x=.8f))) }
        assertEquals("TRANSFERRED",r.s.event);val once=r.s.eventSerial
        repeat(24) { r.frame(listOf(h(x=.25f),h(x=.8f))) }
        assertEquals(once,r.s.eventSerial)
    }

    @Test fun takeoverCannotInheritAnUnfinishedDirectionalDwell() {
        val r=Rig();r.claim();r.frame(listOf(h("POINT_UP")));assertTrue(r.sent.isEmpty())
        r.frame(emptyList());repeat(5) { r.frame(listOf(h(x=.8f))) }
        assertEquals("TRANSFERRED",r.s.event);assertTrue(r.sent.isEmpty())
        r.frame(listOf(h("POINT_DOWN",x=.8f)));assertTrue(r.sent.isEmpty())
        repeat(4) { r.frame(listOf(h("POINT_DOWN",x=.8f))) }
        assertEquals(listOf(AirGestures.Command.DOWN),r.sent)
    }

}
