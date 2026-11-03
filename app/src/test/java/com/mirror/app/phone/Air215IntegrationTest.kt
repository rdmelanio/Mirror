package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class Air215IntegrationTest {
    private fun points(pose:String,x:Float=.5f,y:Float=.5f,angle:Float=0f):List<AirPointing.Point> {
        val p=MutableList(21) { AirPointing.Point(.5f,.57f) }
        p[0]=AirPointing.Point(.5f,.7f)
        for((m,extended) in listOf(5 to (pose=="INDEX" || pose=="Victory"),9 to (pose=="Victory"),13 to false,17 to false)) {
            val fx=.5f+(m-5)*.008f;p[m]=AirPointing.Point(fx,.5f);p[m+1]=AirPointing.Point(fx,.43f);p[m+2]=AirPointing.Point(fx,if(extended) .34f else .55f);p[m+3]=AirPointing.Point(fx,if(extended) .25f else .58f)
        }
        p[2]=AirPointing.Point(.47f,.6f);p[3]=AirPointing.Point(.48f,.57f);p[4]=AirPointing.Point(.51f,.54f)
        val radians=angle*PI/180
        // Small, distant hand with real joint geometry. Palm/wrist center moves independently of finger direction.
        val transformed=p.map { v ->
            val dx=(v.x-.5f)*.075f;val dy=(v.y-.55f)*.075f
            AirPointing.Point(x+(dx*cos(radians)-dy*sin(radians)).toFloat(),y+(dx*sin(radians)+dy*cos(radians)).toFloat())
        }
        val center=listOf(0,5,9,13,17).map { transformed[it] };val ox=center.map { it.x }.average().toFloat()-x;val oy=center.map { it.y }.average().toFloat()-y
        return transformed.map { AirPointing.Point(it.x-ox,it.y-oy) }
    }
    @Test fun actualLandmarksThroughSessionAndBoundedQueueDeliverAllDirectionsAndCoreCommands() {
        for(step in listOf(280L,430L)) for(mirrored in listOf(false,true)) {
            var time=0L;var claims=0
            val events=mutableListOf<String>();val delivered=mutableListOf<AirGestures.Command>()
            val queue=AirKeyQueue(event={stage,cmd,_->events.add("$stage:$cmd")})
            val session=AirControlSession({claims++},{queue.offer(it,time)},true)
            fun frame(pose:String,x:Float=.5f,y:Float=.5f,angle:Float=0f,native:String=if(pose=="INDEX") "None" else pose,dt:Long=step) {
                time+=dt
                val hand=AirObservation.hand(points(pose,if(mirrored) 1-x else x,y,angle),native,if(native=="None") .2f else .82f,mirrored,false,.65f)
                session.update(time,listOf(hand),true,false,dt,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
                queue.take(time+20)?.let { delivered.add(it.command);queue.sent(it,time+25,5) }
            }
            repeat(6) { frame("Open_Palm") };repeat(3) { frame("INDEX") }
            for((dx,dy) in listOf(.004f to 0f,-.004f to 0f,0f to -.004f,0f to .004f)) {
                for(i in 1..5) frame("INDEX",.5f+i*dx,.5f+i*dy,angle=when(i) { 2 -> 45f;3 -> 85f;else -> 0f })
                repeat(3) { frame("INDEX") }
            }
            repeat(4) { frame("Thumb_Up") };repeat(4) { frame("Thumb_Down") };repeat(5) { frame("Victory") }
            assertEquals(listOf(AirGestures.Command.RIGHT,AirGestures.Command.LEFT,AirGestures.Command.UP,AirGestures.Command.DOWN,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),delivered)
            assertEquals(1,claims);assertTrue(session.sessionActive)
            assertEquals(delivered.size,events.count { it.startsWith("QUEUED") });assertEquals(delivered.size,events.count { it.startsWith("TRANSMITTED") })
        }
    }
    @Test fun classificationFlickerAndMissingLandmarksPauseKeepCenterAndRecoverWithoutReplay() {
        var now=0L;var claims=0;val sent=mutableListOf<AirGestures.Command>();val s=AirControlSession({claims++},{sent.add(it)},true)
        fun f(visible:Boolean,x:Float=.5f,index:Boolean=true,dt:Long=350) { now+=dt;s.update(now,if(visible) listOf(AirOwnership.Hand(x,.5f,.015f,if(index) "None" else "Open_Palm",.83f,index)) else emptyList(),true,false,dt,.65f,50,false,400,60_000,AirNavigation.JOYSTICK) }
        repeat(5) { f(true,index=false) };repeat(3) { f(true) }
        repeat(3) { f(false) };assertTrue(s.sessionActive);assertTrue(s.processing(now))
        repeat(5) { f(true) };assertTrue(s.trackingOwned);assertTrue(sent.isEmpty())
        for(i in 1..5) f(true,x=.5f+i*.004f)
        assertEquals(listOf(AirGestures.Command.RIGHT),sent);assertEquals(1,claims)
        repeat(12) { f(false) };assertFalse(s.processing(now));assertTrue(s.sessionActive)
    }
    @Test fun inputFallbackIsOnceAutomaticClosesResourcesAndNeverRetriesBadRoutePerFrame() {
        class Image:AutoCloseable { var closed=false;override fun close() { closed=true } }
        var reports=0;var attempts=0;val seen=mutableListOf<Image>();val p=AirInputPolicy { reports++ }
        repeat(100) {
            assertEquals(7,p.process("auto",{ bitmap -> attempts++;if(!bitmap) throw IllegalArgumentException("The size of the buffer should be: 691200 but is 1228800") else Image().also { seen.add(it) } },{7}))
        }
        assertEquals(101,attempts);assertEquals(1,reports);assertTrue(p.bitmapFallback)
        // Construction failed before ownership was returned; every constructed resource closes.
        assertTrue(seen.all { it.closed })
    }
    @Test fun queueEventsAreOrderedAndCriticalDiagnosticsAreNotFrameThrottled() {
        var now=100L;val d=AirDiagnostics { now };val q=AirKeyQueue(event={a,b,c->d.command(a,b,c)})
        d.command("ACCEPTED",AirGestures.Command.RIGHT,0);q.offer(AirGestures.Command.RIGHT,now);val k=q.take(++now)!!;q.sent(k,++now,1)
        val text=d.text();assertTrue(text.indexOf("ACCEPTED")<text.indexOf("QUEUED"));assertTrue(text.indexOf("QUEUED")<text.indexOf("TRANSMITTED"))
        assertEquals("Joystick: Index valid",AirDiagnostics.reason("Active · Joystick: Index valid"))
    }
}
