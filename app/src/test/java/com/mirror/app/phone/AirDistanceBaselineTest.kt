package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirDistanceBaselineTest {
    @Test fun confidentlyDetectedSmallHandCanClaimAndUseValidatedPointing() {
        val s=AirControlSession(household=true)
        for(t in 0L..1600 step 200) s.update(t,listOf(AirOwnership.Hand(.5f,.5f,.01f,"Open_Palm",.78f)),true,false,200,.65f,50,false)
        assertTrue("Full-camera hand scale must not be confused with classifier confidence",s.sessionActive)
    }
    @Test fun validatedIndexGeometryIsScaleInvariantAfterRoiMapping() {
        val p=MutableList(21) { AirPointing.Point(.51f,.57f) }
        p[0]=AirPointing.Point(.5f,.7f);p[5]=AirPointing.Point(.5f,.5f);p[6]=AirPointing.Point(.5f,.4f);p[8]=AirPointing.Point(.5f,.2f)
        p[9]=AirPointing.Point(.53f,.5f);p[10]=AirPointing.Point(.53f,.44f);p[12]=AirPointing.Point(.53f,.58f)
        p[13]=AirPointing.Point(.56f,.51f);p[14]=AirPointing.Point(.56f,.46f);p[16]=AirPointing.Point(.56f,.59f)
        p[17]=AirPointing.Point(.59f,.53f);p[18]=AirPointing.Point(.59f,.48f);p[20]=AirPointing.Point(.59f,.6f)
        p[2]=AirPointing.Point(.47f,.6f);p[3]=AirPointing.Point(.48f,.57f);p[4]=AirPointing.Point(.51f,.54f)
        for(scale in listOf(1f,.1f,.05f)) assertEquals("POINT_UP",AirPointing.classify(p.map { AirPointing.Point(.5f+(it.x-.5f)*scale,.5f+(it.y-.5f)*scale) },false))
    }
}
