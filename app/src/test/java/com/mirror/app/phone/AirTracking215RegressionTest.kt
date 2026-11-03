package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirTracking215RegressionTest {
    private fun hand(p:String="POINT_UP",x:Float=.5f)=AirOwnership.Hand(x,.5f,.015f,p,.82f)
    private fun claim(s:AirControlSession) { for(t in 0L..1400 step 350) s.update(t,listOf(hand("Open_Palm")),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK) }
    @Test fun smallHandCanMoveAcrossIrregularFrameGapWithoutLosingItsOwner() {
        val s=AirControlSession(household=true);claim(s)
        s.update(2250,listOf(hand(x=.56f)),true,false,850,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        s.update(2600,listOf(hand(x=.56f)),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        assertTrue(s.sessionActive);assertTrue("A plausible 850 ms hand movement cannot strand the owner",s.trackingOwned)
    }
    @Test fun tiedCompetingMatchesResolveOnlyWhenOriginalOwnerIsUniquelyCloserAgain() {
        val s=AirControlSession(household=true);claim(s)
        for(t in listOf(1750L,2100L)) s.update(t,listOf(hand(x=.49f),hand(x=.51f)),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        assertEquals("AMBIGUOUS",s.trackingState)
        for(t in 2450L..4200 step 350) s.update(t,listOf(hand("Closed_Fist",.5f)),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK)
        assertTrue(s.sessionActive);assertTrue("Fresh evidence at the original center resolves a previous matching tie",s.trackingOwned)
    }
    @Test fun briefOcclusionRetainsTheOriginalNeutralCenterAndDoesNotReplayMovement() {
        val sent=mutableListOf<AirGestures.Command>();val s=AirControlSession(send={sent.add(it)},household=true);claim(s)
        var t=1400L
        fun f(h:AirOwnership.Hand?) { t+=350;s.update(t,listOfNotNull(h),true,false,350,.65f,50,false,400,60_000,AirNavigation.JOYSTICK) }
        repeat(3) { f(hand()) };repeat(2) { f(null) }
        repeat(4) { f(hand(x=.51f)) };assertTrue(sent.isEmpty());assertTrue(s.sessionActive)
        f(hand()) // Revalidate the retained center, not a silently moved anchor.
        f(hand(x=.519f))
        assertEquals(listOf(AirGestures.Command.RIGHT),sent)
    }
}
