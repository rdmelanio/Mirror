package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test

class AirFinalNavigationTest {
    private fun h(p:String="POINT_UP",x:Float=.4f,y:Float=.5f,size:Float=.08f)=AirOwnership.Hand(x,y,size,p,.76f)
    @Test fun joystickFourExcursionsCenteringJitterAndBoundedRepeat() {
        for((dx,dy,expected) in listOf(Triple(.09f,0f,AirGestures.Command.RIGHT),Triple(-.09f,0f,AirGestures.Command.LEFT),Triple(0f,-.09f,AirGestures.Command.UP),Triple(0f,.09f,AirGestures.Command.DOWN))) {
            val j=AirJoystick();assertNull(j.update(0,h(),50));assertNull(j.update(200,h(),50))
            repeat(8) { assertNull(j.update(250L+it*100,h(x=.4f+(it%2)*.001f),50)) }
            assertEquals(expected,j.update(1200,h(x=.4f+dx,y=.5f+dy),50))
            assertNull(j.update(1450,h(x=.4f+dx,y=.5f+dy),50))
            assertEquals(expected,j.update(2250,h(x=.4f+dx,y=.5f+dy),50))
            j.update(2500,h(),50);j.update(2700,h(),50)
            assertEquals(expected,j.update(3000,h(x=.4f+dx,y=.5f+dy),50))
        }
    }
    @Test fun continuousProductionSessionJoystickStaticTransitionsAndModeChange() {
        for(step in listOf(100L,310L,470L)) {
            var now=0L;var claims=0;val sent=mutableListOf<AirGestures.Command>()
            val s=AirControlSession({claims++},{sent.add(it)},true)
            var mode=AirNavigation.JOYSTICK
            fun frame(hand:AirOwnership.Hand) { now+=step;s.update(now,listOf(hand),true,false,step,.65f,50,false,300,60_000,mode) }
            fun hold(p:String,ms:Long,x:Float=.4f,y:Float=.5f) { val until=now+ms;while(now<until) frame(h(p,x,y)) }
            hold("Open_Palm",1600);assertTrue(s.sessionActive)
            hold("POINT_UP",600);frame(h(x=.50f));hold("Closed_Fist",600)
            hold("Thumb_Up",1100);hold("Thumb_Down",1100);hold("Victory",1300)
            assertEquals(listOf(AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),sent)
            mode=AirNavigation.POINTING
            hold("POINT_UP",1600);assertEquals(4,sent.size);assertTrue(s.sessionActive)
            hold("Open_Palm",600);hold("POINT_UP",1100)
            assertEquals(AirGestures.Command.UP,sent.last());assertEquals(5,sent.size);assertEquals(1,claims)
        }
    }
    @Test fun thumbsDownAndStandaloneDownNeverBecomeJoystickDown() {
        val j=AirJoystick();j.update(0,h(),50);j.update(200,h(),50)
        for(pose in listOf("Thumb_Down","POINT_DOWN","Thumb_Up","Victory","Closed_Fist")) repeat(8) { assertNull(j.update(300L+it*350,h(pose,y=.8f),50)) }
    }
    @Test fun sensitivityAndDropoutSafetyAndRecenterAfterPoseChange() {
        val fine=AirJoystick();val coarse=AirJoystick()
        for(j in listOf(fine,coarse)) { j.update(0,h(),50);j.update(200,h(),50) }
        assertEquals(AirGestures.Command.RIGHT,fine.update(500,h(x=.46f),100));assertNull(coarse.update(500,h(x=.46f),0))
        assertNull(fine.update(800,h("None",x=.7f),100));assertNull(fine.update(1100,h("Thumb_Up",x=.7f),100))
        assertNull(fine.update(1400,h(x=.7f),100));assertNull(fine.update(1600,h(x=.7f),100))
    }
    @Test fun distinctStaticGesturesDoNotWaitForUnrelatedPreviousCommandsCooldown() {
        val p=AirPointCommands();assertNull(p.update(0,"POINT_UP",.78f,.65f))
        assertEquals(AirGestures.Command.UP,p.update(400,"POINT_UP",.78f,.65f))
        assertNull(p.update(650,"Thumb_Up",.78f,.65f))
        assertEquals(AirGestures.Command.OK,p.update(950,"Thumb_Up",.78f,.65f))
        assertNull(p.update(1100,"Thumb_Down",.78f,.65f))
        assertEquals(AirGestures.Command.BACK,p.update(1400,"Thumb_Down",.78f,.65f))
        repeat(10) { assertNull(p.update(1600L+it*300,"Thumb_Down",.78f,.65f)) }
    }
    @Test fun queueIsOrderedBoundedExpiringAndNoReplayAfterReconnect() {
        val q=AirKeyQueue();assertTrue(q.offer(AirGestures.Command.UP,100));assertTrue(q.offer(AirGestures.Command.RIGHT,120));assertFalse(q.offer(AirGestures.Command.DOWN,130))
        val first=q.take(140)!!;assertEquals(AirGestures.Command.UP,first.command);assertEquals(40,q.queueMs);q.sent(first,150,10)
        assertEquals(AirGestures.Command.RIGHT,q.take(150)!!.command);assertNull(q.take(160))
        q.offer(AirGestures.Command.HOME,200);assertNull(q.take(601));assertEquals(2L,q.dropped)
        q.offer(AirGestures.Command.BACK,700);q.clear();assertNull(q.take(710));assertTrue(q.summary().contains("write 10ms"))
    }
    @Test fun directionLabelsDoNotRequireModelProbabilityAndNoneDoesNotMeanHandLost() {
        val sent=mutableListOf<AirGestures.Command>();val s=AirControlSession(send={sent.add(it)},household=true);var now=0L
        fun frame(p:String,score:Float=.75f) { now+=330;s.update(now,listOf(h(p).copy(confidence=score)),true,false,330,.75f,50,false) }
        repeat(5) { frame("Open_Palm") };repeat(5) { frame("POINT_DOWN",.1f) }
        repeat(8) { frame("None",.01f) };assertTrue(s.trackingOwned);assertTrue(s.sessionActive)
        assertEquals(listOf(AirGestures.Command.DOWN),sent)
    }
}
