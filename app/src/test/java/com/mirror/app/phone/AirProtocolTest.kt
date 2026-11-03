package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AirProtocolTest {
    private fun fixture(name: String): ByteArray = javaClass.getResource("/air-remote/$name.hex")!!.readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun active(now: Long=0): AirSession = AirSession(now).also {
        val configured=AirWire.fields(it.receive(fixture("configure"))!!.let { r -> AirWire.fields(r).getValue(1) })
        assertEquals(35,AirWire.value(configured[1]))
        val info=AirWire.fields(configured.getValue(2))
        assertEquals("com.mirror.app",info.getValue(5).toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x12,2,8,35),it.receive(fixture("active")))
        assertFalse(it.ready)
    }
    @Test fun powerFeatureStartAndKeyMatchIndependentSchema() {
        val s=active();s.receive(fixture("start"));assertTrue(s.ready)
        assertArrayEquals(fixture("right"),s.key(AirGestures.Command.RIGHT))
        val off=active();off.receive(fixture("off"));assertTrue(off.ready)
    }
    @Test fun positiveAndNegativePingMatchSchema() {
        val s=active()
        assertArrayEquals(fixture("pong"),s.receive(fixture("ping")))
        assertArrayEquals(fixture("negative_pong"),s.receive(fixture("negative_ping")))
        assertFalse(s.ready)
    }
    @Test fun pingsCannotExtendHandshakeDeadlineAndNewConnectionRecovers() {
        val s=active(100)
        for(t in 100L..12000L step 100) { s.receive(fixture("ping"));s.checkDeadline(t) }
        try { s.checkDeadline(12100);fail("deadline") } catch(e:IllegalStateException) { assertTrue(e.message!!.contains("RemoteStart")) }
        val retry=active(13000);retry.receive(fixture("start"));retry.checkDeadline(99000);assertTrue(retry.ready)
    }
    @Test fun serverWithoutPowerBecomesReadyAfterActive() {
        val s=AirSession();s.receive(byteArrayOf(10,2,8,3));assertFalse(s.ready)
        s.receive(byteArrayOf(18,2,8,3));assertTrue(s.ready)
    }
    @Test fun outOfOrderStartCannotEnableKeys() {
        val s=AirSession()
        try { s.receive(fixture("start"));fail("order") } catch(_:IllegalStateException) {}
        assertFalse(s.ready)
    }
    @Test fun fragmentedServerTranscriptAllowsRightOnlyAfterReadiness() {
        val executor=Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val task=executor.submit<Boolean> {
                server.accept().use { peer ->
                    peer.soTimeout=3000
                    fun send(name:String) { val out=java.io.ByteArrayOutputStream();AirWire.write(out,fixture(name));out.toByteArray().forEach { peer.outputStream.write(it.toInt());peer.outputStream.flush() } }
                    send("configure");val response=AirWire.read(peer.inputStream)
                    assertEquals(35,AirWire.value(AirWire.fields(AirWire.fields(response).getValue(1))[1]))
                    send("active");assertArrayEquals(byteArrayOf(18,2,8,35),AirWire.read(peer.inputStream))
                    send("ping");assertArrayEquals(fixture("pong"),AirWire.read(peer.inputStream))
                    send("start");assertArrayEquals(fixture("right"),AirWire.read(peer.inputStream))
                    true
                }
            }
            try {
                Socket("127.0.0.1",server.localPort).use { client ->
                    client.soTimeout=3000;val s=AirSession()
                    repeat(4) {
                        if(!s.ready) try { s.key(AirGestures.Command.RIGHT);fail("premature key") } catch(_:IllegalStateException) {}
                        s.receive(AirWire.read(client.inputStream))?.let { AirWire.write(client.outputStream,it) }
                    }
                    assertTrue(s.ready);AirWire.write(client.outputStream,s.key(AirGestures.Command.RIGHT))
                }
                assertTrue(task.get(5,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
