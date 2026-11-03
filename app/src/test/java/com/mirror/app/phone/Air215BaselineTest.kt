package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class Air215BaselineTest {
    private fun h(p:String="Open_Palm",x:Float=.5f,y:Float=.5f)=AirOwnership.Hand(x,y,.015f,p,.84f)
    private fun run(mode:String):Pair<List<AirGestures.Command>,AirControlSession> {
        var t=0L;val q=AirKeyQueue();val sent=mutableListOf<AirGestures.Command>()
        val s=AirControlSession(send={ q.offer(it,t) },household=true)
        fun f(hand:AirOwnership.Hand?) { t+=if(t%3==0L) 310 else 420;s.update(t,listOfNotNull(hand),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
            q.take(t+10)?.let { sent.add(it.command);q.sent(it,t+15,5) } }
        repeat(5) { f(h()) };repeat(3) { f(h("POINT_UP")) }
        for(i in 1..4) f(h(if(mode=="rotating") "POINT_RIGHT" else "POINT_UP",x=.5f+i*.007f))
        return sent to s
    }
    @Test fun indexOrientationFlickerDuringGradualMovementStillDeliversRight() { assertEquals(listOf(AirGestures.Command.RIGHT),run("rotating").first) }
    @Test fun joystickMovementAndOwnerMatcherHaveCompatibleCorridors() { assertEquals(listOf(AirGestures.Command.RIGHT),run("moving").first) }
    @Test fun recoveryDoesNotDropToEcoAfterOneSecond() {
        val s=AirControlSession(household=true)
        for(t in 0L..1600 step 320) s.update(t,listOf(h()),true,false,320,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        s.update(2000,emptyList(),true,false,400,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        assertTrue(s.sessionActive);assertTrue("A bounded faster recovery search must outlive 1 second",s.processing(2800))
    }
}
