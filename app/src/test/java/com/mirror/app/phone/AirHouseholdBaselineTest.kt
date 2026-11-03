package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test

class AirHouseholdBaselineTest {
    private fun h(name:String="Open_Palm",x:Float=.5f,score:Float=.78f,size:Float=.1f)=AirOwnership.Hand(x,.5f,size,name,score)
    private fun claimed():AirControlSession = AirControlSession(household=true).also { s ->
        for(t in 0L..2800 step 350) s.update(t,listOf(h()),true,false,350,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
    @Test fun householdClaimTakesOneSecondWithFlicker() {
        val s=AirControlSession(household=true)
        for(t in listOf(0L,400,600,800,1100,1300)) s.update(t,listOf(h(if(t==600L) "None" else "Open_Palm")),true,false,200,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
    @Test fun prolongedHandAbsenceDoesNotEndValidSession() {
        val s=claimed();s.update(5000,emptyList(),true,false,400,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
    @Test fun remoteDisconnectDoesNotEraseClaim() {
        val s=claimed();s.update(3200,listOf(h("Thumb_Up")),false,false,400,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
    @Test fun defaultSessionSurvivesThirtySecondsWithoutInteraction() {
        val s=claimed()
        for(t in 3200L..36_000 step 400) s.update(t,listOf(h("Closed_Fist")),true,false,400,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
    @Test fun isolatedNoneDoesNotRestartStaticDwell() {
        val p=AirPointCommands()
        assertNull(p.update(0,"Thumb_Up",.78f,.65f));assertNull(p.update(350,"None",.2f,.65f))
        assertEquals(AirGestures.Command.OK,p.update(700,"Thumb_Up",.8f,.65f))
    }
    @Test fun anotherMemberCanClaimWithoutEndingHouseholdSession() {
        val s=claimed();s.update(3300,emptyList(),true,false,400,.65f,50,false)
        for(t in 3700L..5100 step 350) s.update(t,listOf(h(x=.85f)),true,false,350,.65f,50,false)
        assertTrue(s.gestures.armed)
    }
}
