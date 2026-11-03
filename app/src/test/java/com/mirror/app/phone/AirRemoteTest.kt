package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AirRemoteTest {
    private fun armed(): AirGestures = AirGestures().also { g -> for (t in 0L..900L step 100) g.update(t, "Open_Palm", .95f, .5f, .5f); assertTrue(g.armed) }
    @Test fun palmMustBeStillAndConfident() {
        val g = AirGestures()
        for (t in 0L..2000L step 100) g.update(t, "Open_Palm", .6f, .5f, .5f)
        assertFalse(g.armed)
        for (t in 2100L..4100L step 100) g.update(t, "Open_Palm", .9f, if (t % 200 == 0L) .2f else .8f, .5f)
        assertFalse(g.armed)
    }
    @Test fun allDirectionsAndSingleSwipe() {
        listOf(Triple(.8f,.5f,AirGestures.Command.RIGHT), Triple(.2f,.5f,AirGestures.Command.LEFT), Triple(.5f,.2f,AirGestures.Command.UP), Triple(.5f,.8f,AirGestures.Command.DOWN)).forEach { (x,y,key) ->
            val g=armed(); assertEquals(key,g.update(1100,"Open_Palm",.9f,x,y))
            for(t in 1200L..2200L step 100) assertNull(g.update(t,"Open_Palm",.9f,x,y))
        }
    }
    @Test fun staticGesturesDebounceLatchAndCooldown() {
        for ((name,key) in listOf("Closed_Fist" to AirGestures.Command.OK,"Victory" to AirGestures.Command.BACK,"Thumb_Up" to AirGestures.Command.HOME)) {
            val g=armed(); assertNull(g.update(1000,name,.9f,.5f,.5f)); assertNull(g.update(1200,name,.9f,.5f,.5f))
            assertEquals(key,g.update(1300,name,.9f,.5f,.5f))
            for(t in 1400L..2400L step 100) assertNull(g.update(t,name,.9f,.5f,.5f))
            g.update(2500,"",0f,0f,0f); g.update(2800,"",0f,0f,0f); g.update(2900,name,.9f,.5f,.5f)
            assertEquals(key,g.update(3200,name,.9f,.5f,.5f))
        }
    }
    @Test fun oneBadFrameDoesNotRepeatHeldGesture() {
        val g=armed(); g.update(1000,"Closed_Fist",.9f,.5f,.5f)
        assertEquals(AirGestures.Command.OK,g.update(1300,"Closed_Fist",.9f,.5f,.5f))
        for(t in 1400L..1900L step 100) assertNull(g.update(t,"Closed_Fist",.9f,.5f,.5f))
        g.update(2000,"",0f,0f,0f)
        g.update(2100,"Closed_Fist",.9f,.5f,.5f)
        assertNull(g.update(2500,"Closed_Fist",.9f,.5f,.5f))
    }
    @Test fun inactivityAndLostFramesRequireRearming() {
        val g=armed(); assertNull(g.update(16000,"Closed_Fist",.9f,.5f,.5f)); assertFalse(g.armed)
        val h=AirGestures(); h.update(0,"Open_Palm",.9f,.5f,.5f); h.update(1000,"Open_Palm",.9f,.5f,.5f); assertFalse(h.armed)
    }
    @Test fun commandWireAndPairingEnvelope() {
        for(key in AirGestures.Command.entries) {
            val fields=AirWire.fields(AirWire.fields(AirWire.key(key)).getValue(10))
            assertEquals(key.key,AirWire.value(fields[1])); assertEquals(3,AirWire.value(fields[2]))
        }
        val f=AirWire.fields(AirWire.pairing(10,AirWire.text(1,"atvremote")))
        assertEquals(2,AirWire.value(f[1])); assertEquals(200,AirWire.value(f[2])); assertTrue(10 in f)
    }
    @Test fun framingSupportsFragmentedReadsAndRejectsOversize() {
        val bytes=ByteArray(200) { 7 }; val output=ByteArrayOutputStream(); AirWire.write(output,bytes)
        val input=object: ByteArrayInputStream(output.toByteArray()) { override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,3)) }
        assertArrayEquals(bytes,AirWire.read(input))
        try { AirWire.read(ByteArrayInputStream(byteArrayOf(-1,-1,127))); fail("oversize") } catch(_:IllegalArgumentException) {}
        try { AirWire.fields(byteArrayOf(10,127)); fail("truncated") } catch(_:IllegalArgumentException) {}
    }
    @Test fun remoteNegotiationPingAndNoCommandsBeforeHandshake() {
        val session = AirSession()
        try { session.key(AirGestures.Command.HOME); fail("unready") } catch (_:IllegalStateException) {}
        val configure=session.receive(AirWire.bytes(1,AirWire.number(1,3)))!!
        assertEquals(3,AirWire.value(AirWire.fields(AirWire.fields(configure).getValue(1))[1]))
        assertTrue(2 in AirWire.fields(session.receive(AirWire.bytes(2,AirWire.number(1,3)))!!))
        val ping=session.receive(AirWire.bytes(8,AirWire.number(1,987)))!!
        assertEquals(987,AirWire.value(AirWire.fields(AirWire.fields(ping).getValue(9))[1]))
        assertFalse(session.ready)
        session.receive(AirWire.bytes(40,AirWire.number(1,1))); assertTrue(session.ready)
        assertArrayEquals(AirWire.key(AirGestures.Command.OK),session.key(AirGestures.Command.OK))
        assertFalse(AirSession().ready)
        try { AirSession().receive(AirWire.bytes(1,AirWire.number(1,1))); fail("no keys") } catch (_:IllegalStateException) {}
        try { session.receive(AirWire.bytes(3,byteArrayOf())); fail("error") } catch (_:IllegalStateException) {}
    }
}
