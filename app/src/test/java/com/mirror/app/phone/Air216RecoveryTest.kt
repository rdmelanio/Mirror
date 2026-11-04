package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class Air216RecoveryTest {
 private fun h(x:Float=.5f,p:String="Closed_Fist")=AirOwnership.Hand(x,.5f,.03f,p,.9f)
 private fun frame(s:AirControlSession,t:Long,vararg h:AirOwnership.Hand)=s.update(t,h.toList(),true,false,200,.65f,50,false)
 private fun claimed():AirControlSession=AirControlSession(household=true).also { s -> for(t in 0L..1200 step 200) frame(s,t,h(p="Open_Palm")) }
 @Test fun staleAmbiguityAllowsFreshSingleControllerAwayFromOldCenter() {
  val s=claimed()
  frame(s,1400,h(.49f),h(.51f))
  for(t in 1600L..5000 step 200) frame(s,t)
  for(t in 5200L..6200 step 200) frame(s,t,h(.7f))
  assertTrue(s.sessionActive);assertTrue(s.trackingOwned);assertNotEquals("TRANSFERRED",s.event)
 }
 @Test fun recoverySamplingSurvivesSlowReacquisition() {
  val s=claimed();frame(s,1400)
  assertTrue(s.recovering(5000));assertFalse(s.recovering(20000))
 }
 @Test fun joystickRecentersAtFreshStablePositionWithoutOldDirection() {
  val j=AirJoystick()
  fun index(x:Float)=h(x,"POINT_UP")
  j.update(0,index(.5f),50);j.update(200,index(.5f),50)
  assertEquals(AirGestures.Command.RIGHT,j.update(400,index(.6f),50))
  j.pause(600)
  for(t in 800L..1600 step 200) assertNull(j.update(t,index(.7f),50))
  assertEquals(AirGestures.Command.RIGHT,j.update(1800,index(.8f),50))
 }
 @Test fun passiveRecenteringDoesNotCountAsDeliberateInteraction() {
  val j=AirJoystick()
  for(t in 0L..5000 step 200) {
   j.pause(t);assertNull(j.update(t+50,h(p="POINT_UP"),50));assertNull(j.update(t+100,h(p="POINT_UP"),50))
  }
  assertEquals(-1L,j.activityAt)
 }

}
