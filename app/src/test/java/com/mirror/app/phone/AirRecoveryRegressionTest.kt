package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class AirRecoveryRegressionTest {
    private class Rig(val threshold:Float=.65f) {
        var now=0L;var claims=0
        val sent=mutableListOf<AirGestures.Command>()
        val s=AirControlSession({claims++},{sent.add(it)})
        fun frame(name:String,score:Float=.78f,x:Float=.5f,size:Float=.1f,dt:Long=370) {
            now+=dt;s.update(now,listOf(AirOwnership.Hand(x,.5f,size,name,score)),true,false,400,threshold,50,false,400)
        }
        fun claim() { repeat(8) { frame("Open_Palm",.98f) };assertTrue(s.gestures.armed);assertTrue(sent.isEmpty()) }
        fun pose(name:String,score:Float=.78f) { repeat(5) { frame(name,score,dt=if(it%2==0) 330 else 470) } }
    }
    @Test fun neutralNoneEmptyAndLowConfidenceRearmSamePointWithoutNewClaim() {
        for(neutral in listOf("None","","Open_Palm")) {
            val r=Rig();r.claim();r.pose("POINT_UP")
            repeat(3) { r.frame(neutral,if(neutral=="Open_Palm") .3f else .2f,dt=if(it==1) 460 else 310) }
            r.pose("POINT_UP")
            assertEquals(neutral,listOf(AirGestures.Command.UP,AirGestures.Command.UP),r.sent);assertEquals(1,r.claims)
        }
    }
    @Test fun briefShapeSizeMismatchPausesThenReacquiresSameNearbyHand() {
        val r=Rig();r.claim();r.pose("POINT_UP")
        r.frame("None",.3f,x=.57f,size=.39f,dt=350) // ratio exceeds old 3.5 limit
        assertTrue("brief mismatch must pause, not disarm",r.s.gestures.armed)
        r.frame("Closed_Fist",.8f,x=.55f,size=.065f,dt=410)
        assertTrue(r.s.status.contains("recovered",true));assertTrue(r.s.gestures.armed)
        r.pose("Thumb_Up");assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.OK),r.sent);assertEquals(1,r.claims)
    }
    private fun bentIndex():List<AirPointing.Point> {
        val p=MutableList(21) { AirPointing.Point(.51f,.57f) }
        p[0]=AirPointing.Point(.5f,.7f);p[5]=AirPointing.Point(.5f,.5f);p[6]=AirPointing.Point(.5f,.4f);p[8]=AirPointing.Point(.36f,.25f)
        for((m,j,t) in listOf(Triple(9,10,12),Triple(13,14,16),Triple(17,18,20))) {
            p[m]=AirPointing.Point(.55f,.5f);p[j]=AirPointing.Point(.55f,.44f);p[t]=AirPointing.Point(.55f,.58f)
        }
        p[2]=AirPointing.Point(.47f,.6f);p[3]=AirPointing.Point(.48f,.57f);p[4]=AirPointing.Point(.51f,.54f)
        return p
    }
    @Test fun validGeometryMustNotBeRejectedByClassificationProbabilitySlider() {
        val points=bentIndex();val name=AirPointing.classify(points,false)!!;val alignment=AirPointing.quality(points)
        assertTrue(alignment<.95f)
        val r=Rig(.95f);r.claim();r.pose(name,alignment)
        assertEquals(listOf(AirGestures.Command.UP),r.sent)
    }
    @Test fun nativePointingUpCanReachOwnedStableDispatcher() {
        val r=Rig();r.claim();r.pose("Pointing_Up",.82f)
        assertEquals(listOf(AirGestures.Command.UP),r.sent);assertEquals(1,r.claims)
    }
    @Test fun oneBadClassificationDoesNotReleaseHeldCommand() {
        val r=Rig();r.claim();r.pose("POINT_UP");r.frame("None",.1f,dt=350);r.pose("POINT_UP")
        assertEquals(listOf(AirGestures.Command.UP),r.sent)
    }
    @Test fun intentionalNewPoseNearIdleDeadlineMustFinishDwellButIdleStillTimesOut() {
        val r=Rig();r.claim()
        while(r.now<31_800) r.frame("Closed_Fist",.8f,dt=400)
        r.frame("Victory",.8f,dt=400);r.frame("Victory",.8f,dt=400);r.frame("Victory",.8f,dt=400)
        assertTrue("intentional dwell across deadline must remain armed",r.s.gestures.armed)
        assertEquals(listOf(AirGestures.Command.HOME),r.sent)
    }
    @Test fun safeNativeFallbackIsCorroboratedAndCustomDirectionWins() {
        val p=bentIndex().toMutableList();p[8]=AirPointing.Point(.5f,.38f)
        assertNull(AirPointing.classify(p,false))
        assertEquals("Pointing_Up",AirPointing.resolve(p,false,"Pointing_Up",.82f,.65f))
        assertEquals("Pointing_Up",AirPointing.resolve(p,true,"Pointing_Up",.82f,.65f))
        assertEquals("",AirPointing.resolve(p,false,"Pointing_Up",.5f,.65f))
        val straight=bentIndex().toMutableList();straight[8]=AirPointing.Point(.5f,.2f)
        fun rotate(angle:Double):List<AirPointing.Point> {
            val a=Math.toRadians(angle)
            return straight.map { q -> val x=q.x-.5;val y=q.y-.7
                AirPointing.Point((.5+x*kotlin.math.cos(a)-y*kotlin.math.sin(a)).toFloat(),(.7+x*kotlin.math.sin(a)+y*kotlin.math.cos(a)).toFloat()) }
        }
        assertEquals("POINT_RIGHT",AirPointing.resolve(rotate(90.0),false,"Pointing_Up",.99f,.65f))
        assertEquals("POINT_LEFT",AirPointing.resolve(rotate(90.0),true,"Pointing_Up",.99f,.65f))
        assertEquals("",AirPointing.resolve(rotate(45.0),false,"Pointing_Up",.99f,.65f))
        p[12]=AirPointing.Point(.55f,.2f)
        assertEquals("",AirPointing.resolve(p,false,"Pointing_Up",.99f,.65f))
        assertEquals("",AirPointing.resolve(emptyList(),false,"Pointing_Up",.99f,.65f))
    }
    @Test fun fullSequenceHasOneClaimWithMovingCoordinatesAndChangingSizes() {
        val r=Rig();r.claim()
        fun pose(name:String) { repeat(5) { i -> r.frame(name,.72f+i*.02f,x=.5f+i*.012f,size=.08f+i*.009f,dt=if(i%2==0) 330 else 480) } }
        pose("POINT_UP");pose("None");pose("POINT_UP");pose("POINT_RIGHT");pose("Thumb_Up");pose("Thumb_Down");pose("Victory")
        assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.UP,AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),r.sent)
        assertEquals(1,r.claims);assertTrue(r.s.gestures.armed)
        assertTrue(r.s.lastCommandAt>=r.s.lastActivityAt)
    }
    @Test fun lowConfidenceHeldLabelCannotCountAsDeliberateNeutralReleaseAndIdleHandExpires() {
        val r=Rig();r.claim();r.pose("Pointing_Up",.8f);r.pose("Pointing_Up",.2f);r.pose("Pointing_Up",.8f)
        assertEquals(listOf(AirGestures.Command.UP),r.sent)
        var sawIdleExpiry=false
        repeat(90) { r.frame("Pointing_Up",.8f,dt=400);sawIdleExpiry=sawIdleExpiry || r.s.status.contains("Disarmed") }
        assertFalse(r.s.gestures.armed);assertTrue(sawIdleExpiry);assertEquals(1,r.sent.size)
    }

}
