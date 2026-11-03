package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test

/** Reproductions of the recorded 2–4 FPS controller trace, through the camera's production boundary. */
class AirFinalBaselineTest {
    private fun h(p:String="Open_Palm",x:Float=.3f,size:Float=.1f,score:Float=.76f)=AirOwnership.Hand(x,.5f,size,p,score)
    private class Rig {
        var now=0L;var claims=0;val sent=mutableListOf<AirGestures.Command>()
        val s=AirControlSession({claims++},{sent.add(it)},household=true)
        fun f(vararg hands:AirOwnership.Hand,dt:Long=310) { now+=dt;s.update(now,hands.toList(),true,false,dt,.65f,50,false) }
        fun claim() { repeat(6) { f(AirOwnership.Hand(.3f,.5f,.1f,"Open_Palm",.76f)) };assertTrue(s.sessionActive) }
    }
    @Test fun overlappingHandsSeparateAndOriginalOwnerRecoversWithoutClaim() {
        val r=Rig();r.claim();repeat(4) { r.f(h("POINT_UP")) };assertEquals(listOf(AirGestures.Command.UP),r.sent)
        repeat(3) { r.f(h("None",x=.29f),h("Closed_Fist",x=.34f)) }
        assertEquals("AMBIGUOUS",r.s.trackingState)
        repeat(5) { r.f(h("Closed_Fist")) }
        assertTrue("Owner must recover after evidence becomes unambiguous",r.s.trackingOwned)
        repeat(5) { r.f(h("Thumb_Up",size=.075f)) }
        assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.OK),r.sent);assertEquals(1,r.claims)
    }
    @Test fun visibleControllerDoesNotFallToEcoAfterTwoSecondsWithoutCommand() {
        val r=Rig();r.claim();repeat(18) { r.f(h("None",score=.15f)) }
        assertTrue(r.s.trackingOwned)
        assertTrue("Visible landmarks preparing a pose need responsive inference",r.s.processing(r.now))
        repeat(10) { r.f() };assertFalse(r.s.processing(r.now));assertTrue(r.s.sessionActive)
    }
    @Test fun remainingCompetitorCannotInheritAmbiguousOwnersPendingCommand() {
        val r=Rig();r.claim();r.f(h("POINT_UP"))
        repeat(3) { r.f(h("POINT_UP",x=.29f),h("Thumb_Up",x=.36f)) }
        repeat(6) { r.f(h("Thumb_Up",x=.36f)) }
        assertTrue(r.s.sessionActive);assertFalse("Rival trajectory cannot inherit authority",r.s.trackingOwned);assertTrue(r.sent.isEmpty())
    }
    @Test fun ordinaryPoseAndScaleTransitionsPreserveOwner() {
        val r=Rig();r.claim()
        for((pose,size) in listOf("POINT_UP" to .1f,"Thumb_Up" to .06f,"None" to .11f,"Thumb_Down" to .08f)) {
            repeat(5) { r.f(h(pose,size=size,score=if(pose=="None") .1f else .76f)) }
            assertTrue(r.s.sessionActive);assertTrue(r.s.trackingOwned)
        }
        assertEquals(1,r.claims);assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.OK,AirGestures.Command.BACK),r.sent)
    }
}
