package com.mirror.app.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class AirFeedbackTest {
    @Test fun authenticatorsBindCursorNonceAndResponse() {
        val key=AirFeedbackAuth.key("paired-token-secret")
        val body=AirFeedbackAuth.request("a".repeat(32),42)
        val mac=AirFeedbackAuth.sign(key,body)
        assertTrue(AirFeedbackAuth.valid(key,body,mac))
        assertFalse(AirFeedbackAuth.valid(key,AirFeedbackAuth.request("b".repeat(32),42),mac))
        assertFalse(AirFeedbackAuth.valid(key,AirFeedbackAuth.request("a".repeat(32),43),mac))
        assertFalse(AirFeedbackAuth.valid(AirFeedbackAuth.key("other"),body,mac))
        assertFalse(AirFeedbackAuth.valid(key,"nonce:altered response",mac))
    }
    @Test fun initialConnectionAndOldEventsNeverReplay() {
        var now=0L;val events=AirFeedbackEvents { now }
        events.post("ACTIVE")
        assertEquals("",JSONObject(events.poll(-1,0)).getString("event"))
        assertEquals("ACTIVE",JSONObject(events.poll(0,0)).getString("event"))
        now=2100;assertEquals("",JSONObject(events.poll(0,0)).getString("event"))
        events.close();assertEquals("",JSONObject(events.poll(0,0)).getString("event"))
    }
    @Test fun captionsAreTransientAndOnlyClaimTransmission() {
        assertEquals(2000L,AirFeedbackDisplay.duration("ACTIVE"))
        for(event in AirFeedbackDisplay.events.filter { it.startsWith("SENT_") }) {
            assertEquals(1200L,AirFeedbackDisplay.duration(event));assertTrue(AirFeedbackDisplay.caption(event)!!.contains("sent"))
        }
        assertNull(AirFeedbackDisplay.caption("EXECUTED"));assertNull(AirFeedbackDisplay.caption("arbitrary markup"))
    }
    @Test fun realListenerRejectsUnknownAndRevokedPairing() {
        val security=PairingAuthority();val code=security.showCode()!!
        val token=security.pair(code,"tv","TV").token!!;val key=AirFeedbackAuth.key(token)
        AirFeedbackServer(security,InetAddress.getLoopbackAddress(),{true},0).use { server ->
            fun request(secret: String, after: Long = -1): JSONObject? = Socket(InetAddress.getLoopbackAddress(),server.port).use { socket ->
                socket.soTimeout=2000
                val nonce=AirFeedbackAuth.nonce()
                val json=JSONObject().put("nonce",nonce).put("after",after).put("mac",AirFeedbackAuth.sign(secret,AirFeedbackAuth.request(nonce,after)))
                socket.getOutputStream().write((json.toString()+"\n").toByteArray())
                runCatching { JSONObject(AirFeedbackServer.readLine(socket.getInputStream())) }.getOrNull()?.also {
                    assertTrue(AirFeedbackAuth.valid(key,"$nonce:${it.getString("body")}",it.getString("mac")))
                }
            }
            assertNotNull(request(key))
            server.post("SENT_RIGHT")
            val received=request(key,0)!!
            assertEquals("SENT_RIGHT",JSONObject(received.getString("body")).getString("event"))
            assertEquals("RIGHT sent",AirFeedbackDisplay.caption("SENT_RIGHT"))
            assertNull(request(AirFeedbackAuth.key("unknown")))
            security.remove(key);assertNull(request(key))
        }
    }
    @Test fun packetSizeIsBounded() {
        try { AirFeedbackServer.readLine("x".repeat(5000).byteInputStream());fail("oversize") } catch(_:IllegalStateException) {}
    }
}
