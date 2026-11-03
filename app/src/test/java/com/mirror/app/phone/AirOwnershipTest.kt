package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class AirOwnershipTest {
    private fun hand(x:Float=.4f,y:Float=.5f,name:String="Open_Palm")=AirOwnership.Hand(x,y,.1f,name,.95f)
    @Test fun firstCompletedPalmClaimsAndOtherHandIsIgnored() {
        val o=AirOwnership()
        for(t in 0L..2000L step 250) {
            val hands=if(t<1000) listOf(hand()) else listOf(hand(.8f),hand())
            val r=o.update(t,hands)
            assertEquals(t==2000L,r.claimed)
        }
        val r=o.update(2100,listOf(hand(.8f),hand(.43f,name="Closed_Fist")))
        assertEquals(.43f,r.hand!!.x);assertEquals("Closed_Fist",r.hand.gesture)
    }
    @Test fun crossingHandsPauseAndRequireNewClaim() {
        val o=AirOwnership();for(t in 0L..2000L step 250) o.update(t,listOf(hand()))
        val r=o.update(2100,listOf(hand(.42f),hand(.46f)))
        assertTrue(r.ambiguous);assertNull(r.hand)
        assertTrue(o.update(2200,listOf(hand(.42f))).lost)
        assertFalse(o.claimed)
    }
    @Test fun lossNeverTransfersOwnershipToAnotherPerson() {
        val o=AirOwnership();for(t in 0L..2000L step 250) o.update(t,listOf(hand()))
        assertTrue(o.update(2100,listOf(hand(.8f))).lost);assertFalse(o.claimed)
        assertFalse(o.update(2200,listOf(hand(.8f))).claimed)
    }
    @Test fun simultaneousPalmsDoNotArbitrarilyChooseAPerson() {
        val o=AirOwnership();var result=AirOwnership.Selection(null)
        for(t in 0L..2500L step 250) result=o.update(t,listOf(hand(.2f),hand(.8f)))
        assertTrue(result.ambiguous);assertFalse(o.claimed)
    }
    @Test fun lowConfidenceAndUnstablePalmsCannotClaim() {
        val o=AirOwnership()
        for(t in 0L..3000L step 250) assertFalse(o.update(t,listOf(hand().copy(confidence=.6f))).claimed)
        for(t in 3250L..6250L step 250) assertFalse(o.update(t,listOf(hand(if(t%500==0L) .2f else .8f))).claimed)
    }
    @Test fun lostFramesDisarmAndResetAllowsAnotherPerson() {
        val o=AirOwnership();for(t in 0L..2000L step 250) o.update(t,listOf(hand()))
        assertTrue(o.update(3500,listOf(hand())).lost)
        for(t in 3250L..5250L step 250) o.update(t,listOf(hand(.8f)))
        assertTrue(o.claimed);o.reset();assertFalse(o.claimed)
    }
    @Test fun inferenceRateLeavesHeadroomAndChangesWithState() {
        val r=AirInferenceRate();assertEquals(333L,r.interval(false));assertEquals(83L,r.interval(true))
        r.measured(300);assertTrue(r.interval(true)>=405);assertTrue(r.interval(false)>=405)
        val overloaded=AirInferenceRate();overloaded.measured(20_000);assertTrue(overloaded.interval(true)>=27_000)
    }
}
