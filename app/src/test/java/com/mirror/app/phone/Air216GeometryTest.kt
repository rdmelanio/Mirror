package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Privacy-safe canonical landmarks, perturbed in scale, handedness and orientation. */
class Air216GeometryTest {
 private fun pose(name:String):List<AirPointing.Point> {
  val p=MutableList(21) { AirPointing.Point(.51f,.57f) }
  p[0]=AirPointing.Point(.5f,.7f)
  for((i,m) in listOf(5,9,13,17).withIndex()) {
   val x=.5f+i*.03f;val extended=name=="Open_Palm" || name=="Victory" && i<2 || name=="POINT_UP" && i==0
   p[m]=AirPointing.Point(x,.5f);p[m+1]=AirPointing.Point(x,.43f);p[m+2]=AirPointing.Point(x,if(extended) .32f else .51f);p[m+3]=AirPointing.Point(x,if(extended) .2f else .58f)
  }
  if(name=="Victory") { p[8]=AirPointing.Point(.45f,.2f);p[12]=AirPointing.Point(.60f,.2f) }
  p[2]=AirPointing.Point(.47f,.6f);p[3]=AirPointing.Point(.48f,.57f);p[4]=AirPointing.Point(.51f,.54f)
  if(name.startsWith("Thumb")) { p[3]=AirPointing.Point(.4f,.55f);p[4]=AirPointing.Point(.3f,if(name=="Thumb_Up") .3f else .9f) }
  return p
 }
 @Test fun requiredVocabularySurvivesScaleAndHandedness() {
  for(name in listOf("Open_Palm","Victory","Closed_Fist","Thumb_Up","Thumb_Down","POINT_UP"))
   for(scale in listOf(.08f,.3f,1f)) for(mirror in listOf(false,true)) {
    val points=pose(name).map { AirPointing.Point(.5f+(it.x-.5f)*scale*(if(mirror) -1 else 1),.5f+(it.y-.5f)*scale) }
    assertEquals("$name scale=$scale mirror=$mirror",name,AirHandGeometry.classify(points).name)
   }
 }
 @Test fun pointingRotationsPreserveDirections() {
  for((angle,name) in listOf(0 to "POINT_UP",90 to "POINT_RIGHT",180 to "POINT_DOWN",270 to "POINT_LEFT")) {
   val a=Math.toRadians(angle.toDouble());val p=pose("POINT_UP").map { val x=it.x-.5;val y=it.y-.7;AirPointing.Point((.5+x*cos(a)-y*sin(a)).toFloat(),(.7+x*sin(a)+y*cos(a)).toFloat()) }
   assertEquals(name,AirHandGeometry.classify(p).name)
  }
 }
 @Test fun confidenceWeightedDwellToleratesOneUnknownWithoutDuplicate() {
  val p=AirPointCommands()
  assertNull(p.update(0,"Thumb_Up",.7f,.65f));assertNull(p.update(170,"None",0f,.65f))
  assertEquals(AirGestures.Command.OK,p.update(350,"Thumb_Up",.9f,.65f))
  for(t in 500L..2000 step 150) assertNull(p.update(t,"Thumb_Up",.9f,.65f))
 }
 @Test fun aspectValidatedDirectionFallbackStillMirrorsHorizontally() {
  val uncertain=List(21) { AirPointing.Point(.5f,.5f) }
  assertEquals("POINT_LEFT",AirPointing.resolve(uncertain,true,"POINT_RIGHT",.85f,.65f))
  assertEquals("POINT_RIGHT",AirPointing.resolve(uncertain,true,"POINT_LEFT",.85f,.65f))
  assertEquals("POINT_RIGHT",AirPointing.resolve(uncertain,false,"POINT_RIGHT",.85f,.65f))
 }

}
