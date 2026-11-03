package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test

class AirReliabilityTest {
    private fun hand(name:String="Open_Palm",score:Float=.70f,x:Float=.5f,y:Float=.5f)=AirOwnership.Hand(x,y,.10f,name,score)
    @Test fun confidenceRangeAndSeventyPercentPalmWithClassificationGaps() {
        assertEquals(50,AirConfidence.percent(0));assertEquals(95,AirConfidence.percent(100))
        for(p in 50..95 step 5) assertEquals(p/100f,AirConfidence.fraction(p))
        val o=AirOwnership();var claim=false
        for(i in 0..7) {
            val h=if(i==2 || i==5) hand("None",.2f) else hand()
            claim=o.update(i*333L,listOf(h)).claimed || claim
            if(i<7) assertFalse(o.claimed)
        }
        assertTrue(claim)
        val strict=AirOwnership().apply { minimumConfidence=.95f }
        for(i in 0..12) assertFalse(strict.update(i*333L,listOf(hand())).claimed)
    }
    @Test fun sporadicPalmAndMovingHandNeverClaim() {
        val o=AirOwnership()
        for(i in 0..20) assertFalse(o.update(i*333L,listOf(hand(if(i%3==0) "Open_Palm" else "None"))).claimed)
        val moving=AirOwnership()
        for(i in 0..20) assertFalse(moving.update(i*333L,listOf(hand(x=if(i%2==0) .4f else .6f))).claimed)
    }
    @Test fun crossingDuringClaimCannotActivate() {
        val o=AirOwnership()
        for(i in 0..5) o.update(i*333L,listOf(hand()))
        assertFalse(o.update(1998,listOf(hand(x=.49f),hand(x=.51f))).claimed)
        assertFalse(o.update(2331,listOf(hand())).claimed)
    }
    @Test fun smallHandSwipeSurvivesClassifierLossInAllDirections() {
        for(command in listOf(AirGestures.Command.LEFT,AirGestures.Command.RIGHT,AirGestures.Command.UP,AirGestures.Command.DOWN)) {
            val g=AirGestures();g.claim(0,.5f,.5f)
            g.update(300,"None",.9f,.5f,.5f);g.update(600,"None",.9f,.5f,.5f)
            assertNull(g.update(900,"Open_Palm",.70f,.5f,.5f,.08f))
            val x=.5f+when(command) { AirGestures.Command.LEFT -> -.14f;AirGestures.Command.RIGHT -> .14f;else -> 0f }
            val y=.5f+when(command) { AirGestures.Command.UP -> -.14f;AirGestures.Command.DOWN -> .14f;else -> 0f }
            assertEquals(command,g.update(1200,"",.2f,x,y,.08f))
            assertNull(g.update(1500,"Open_Palm",.70f,x,y,.08f))
        }
    }
    @Test fun ownedSeventyPercentStaticGesturesDispatchAndDoNotRepeat() {
        for((name,key) in listOf("Closed_Fist" to AirGestures.Command.OK,"Victory" to AirGestures.Command.BACK,"Thumb_Up" to AirGestures.Command.HOME)) {
            val o=AirOwnership();val g=AirGestures();val commands=mutableListOf<AirGestures.Command>()
            for(i in 0..7) { val r=o.update(i*333L,listOf(hand()));if(r.claimed) g.claim(i*333L,.5f,.5f) }
            assertTrue(o.claimed)
            for(t in 2600L..6000L step 340) {
                val h=o.update(t,listOf(hand(name))).hand!!
                g.update(t,h.gesture,h.confidence,h.x,h.y,h.size)?.let { commands.add(it) }
            }
            assertEquals(listOf(key),commands)
        }
    }
    @Test fun slowInferenceGapDoesNotUnnecessarilyDisarm() {
        val o=AirOwnership();for(t in 0L..2400 step 400) o.update(t,listOf(hand()))
        assertNotNull(o.update(3400,listOf(hand())).hand)
        assertTrue(o.update(4900,listOf(hand())).lost)
    }
}
