package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class Air216VisionTest {
 private fun body(x:Float=.5f,quality:Float=.9f):List<AirBodyRegions.Joint> = MutableList(33) { AirBodyRegions.Joint(x,.5f,0f) }.apply {
  this[11]=AirBodyRegions.Joint(x-.1f,.3f,quality);this[12]=AirBodyRegions.Joint(x+.1f,.3f,quality)
  this[13]=AirBodyRegions.Joint(x-.12f,.5f,quality);this[14]=AirBodyRegions.Joint(x+.12f,.5f,quality)
  this[15]=AirBodyRegions.Joint(x-.14f,.6f,quality);this[16]=AirBodyRegions.Joint(x+.14f,.6f,quality)
 }
 @Test fun partialSeatedBodyPredictsBoundedRegionsWithoutLowerBody() {
  val b=AirBodyRegions();b.update(0,listOf(body()))
  assertEquals(1,b.bodies.size);assertEquals(2,b.regions(100,16f/9).size)
  b.regions(100,16f/9).forEach { assertTrue(it.roi.valid());assertNotNull(it.shoulder) }
  val id=b.bodies.single().id;b.update(500,listOf(body(.55f)));assertEquals(id,b.bodies.single().id)
  assertTrue(b.regions(4000,16f/9).isEmpty())
  b.update(1000,emptyList());b.update(2000,listOf(body(.55f)));assertEquals(id,b.bodies.single().id)
 }
 @Test fun uncertainWristExpandsSearchAndMissingPoseDoesNotInventRegions() {
  val b=AirBodyRegions();b.update(0,listOf(body()));val width=b.regions(0,1f).first().roi.width
  b.update(100,listOf(body(quality=.4f)));assertTrue(b.regions(100,1f).first().roi.width>width)
  b.update(200,emptyList());assertTrue(b.regions(200,1f).isEmpty())
 }
 @Test fun cropRemappingAndMirroringUseFullFrame() {
  val roi=AirRoi(.2f,.3f,.4f,.6f);val center=roi.full(AirPointing.Point(.5f,.5f));assertEquals(.3f,center.x,.00001f);assertEquals(.45f,center.y,.00001f)
  val p=List(21) { roi.full(AirPointing.Point(it/30f,it/40f)) }
  val a=AirObservation.hand(p,"None",0f,false,false,.65f);val b=AirObservation.hand(p,"None",0f,true,false,.65f)
  assertEquals(1f-a.x,b.x,.00001f);assertEquals(a.y,b.y,0f)
 }
 @Test fun schedulePreservesClaimBurstAndReducesBodyWorkBeforeHands() {
  val s=AirVisionSchedule();for(t in 0L..1400 step 100) assertTrue(s.discover(t,AirStage.ACTIVE))
  assertFalse(s.discover(2000,AirStage.ACTIVE));assertTrue(s.discover(4500,AirStage.ACTIVE))
  assertTrue(s.poseInterval(AirStage.ACTIVE,3)>s.poseInterval(AirStage.ACTIVE,0))
 }
 @Test fun degenerateAndIncompleteGeometryCannotClaim() {
  assertEquals("None",AirHandGeometry.classify(emptyList()).name)
  assertEquals("None",AirHandGeometry.classify(List(21) { AirPointing.Point(.5f,.5f) }).name)
 }
 @Test fun knownDifferentBodyCannotUseSingleHandRecovery() {
  val s=AirControlSession(household=true)
  fun frame(t:Long,h:AirOwnership.Hand?)=s.update(t,listOfNotNull(h),true,false,200,.65f,50,false)
  val owner=AirOwnership.Hand(.5f,.5f,.04f,"Open_Palm",.9f,bodyId=1)
  for(t in 0L..1200 step 200) frame(t,owner)
  frame(1400,null)
  for(t in 1600L..6000 step 200) frame(t,owner.copy(x=.6f,gesture="POINT_UP",bodyId=2))
  assertTrue(s.sessionActive);assertFalse(s.trackingOwned)
 }
}
