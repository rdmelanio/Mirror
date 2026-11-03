package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class AirPipelineTest {
    @Test fun claimedHandGesturesReachReadySessionOnceAndLossDisarms() {
        val cases=listOf("Open_Palm" to AirGestures.Command.RIGHT,"Open_Palm" to AirGestures.Command.LEFT,
            "Open_Palm" to AirGestures.Command.UP,"Open_Palm" to AirGestures.Command.DOWN,
            "Closed_Fist" to AirGestures.Command.OK,"Victory" to AirGestures.Command.BACK,"Thumb_Up" to AirGestures.Command.HOME)
        for((name,expected) in cases) {
            val owner=AirOwnership();val g=AirGestures();val session=AirSession()
            session.receive(byteArrayOf(10,2,8,35));session.receive(byteArrayOf(18,2,8,35));session.receive(byteArrayOf(0xc2.toByte(),2,2,8,1))
            val sent=mutableListOf<AirGestures.Command>()
            fun frame(t:Long,gesture:String,x:Float=.5f,y:Float=.5f) {
                val selected=owner.update(t,listOf(AirOwnership.Hand(x,y,.12f,gesture,.95f),AirOwnership.Hand(.05f,.05f,.1f,"Victory",.99f)))
                if(selected.claimed) g.claim(t,x,y)
                else selected.hand?.let { h -> g.update(t,h.gesture,h.confidence,h.x,h.y)?.let { command ->
                    val fields=AirWire.fields(AirWire.fields(session.key(command)).getValue(10))
                    assertEquals(expected.key,AirWire.value(fields[1]));sent.add(command)
                } }
            }
            for(t in 0L..2100L step 300) frame(t,"Open_Palm")
            assertTrue(owner.claimed);assertTrue(g.armed);assertTrue(sent.isEmpty())
            for(t in 2200L..2600L step 100) frame(t,"None")
            for(i in 0..5) {
                val delta=i*.05f
                frame(2800L+i*100,name,
                    .5f+when(expected) { AirGestures.Command.RIGHT -> delta;AirGestures.Command.LEFT -> -delta;else -> 0f },
                    .5f+when(expected) { AirGestures.Command.DOWN -> delta;AirGestures.Command.UP -> -delta;else -> 0f })
            }
            assertEquals(listOf(expected),sent)
            val lost=owner.update(3400,emptyList());assertTrue(lost.lost);g.reset();assertFalse(g.armed)
        }
    }
}
