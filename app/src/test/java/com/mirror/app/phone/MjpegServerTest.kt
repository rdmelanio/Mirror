package com.mirror.app.phone

import com.mirror.app.core.MultipartReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.Socket
import org.json.JSONObject

class MjpegServerTest {
    private fun line(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read(); check(b >= 0)
            if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(b)
        }
    }
    @Test fun controlQueryAndStatusSupportTwoConcurrentViewers() {
        val server = MjpegServer("Control camera", StreamStats())
        var received = emptyMap<String, String>()
        server.controls = object : ControlEndpoint {
            override fun status() = JSONObject().put("controls", true).put("zoom", 2.0)
                .put("minZoom", .5).put("maxZoom", 8.0).put("hasFlash", true).put("torch", false)
            override fun control(parameters: Map<String, String>): JSONObject {
                received = parameters
                return status().put("ok", parameters["focus"] != "invalid")
            }
        }
        fun get(path: String, code: String = "200 OK"): JSONObject {
            Socket("127.0.0.1", 8080).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = BufferedInputStream(socket.getInputStream())
                assertEquals("HTTP/1.1 $code", line(input))
                while (line(input).isNotEmpty()) {}
                return JSONObject(input.readBytes().toString(Charsets.UTF_8))
            }
        }
        server.start()
        val viewers = List(2) { Socket("127.0.0.1", 8080).apply { soTimeout = 3000 } }
        try {
            server.publish(byteArrayOf(1, 2), 0)
            viewers.forEach { socket ->
                socket.getOutputStream().write("GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = BufferedInputStream(socket.getInputStream())
                assertEquals("HTTP/1.1 200 OK", line(input))
                while (line(input).isNotEmpty()) {}
            }
            assertTrue(get("/control?zoom=2.25&torch=on&focus=%63enter").getBoolean("ok"))
            assertEquals(mapOf("zoom" to "2.25", "torch" to "on", "focus" to "center"), received)
            val status = get("/status")
            assertEquals(2, status.getInt("clients"))
            assertEquals(.5, status.getDouble("minZoom"), .001)
            assertTrue(status.getBoolean("hasFlash"))
            assertTrue(get("/control?action=snapshot").getBoolean("ok"))
            assertEquals("snapshot", received["action"])
            get("/control?focus=invalid", "400 Bad Request")
        } finally { viewers.forEach { it.close() }; server.close() }
    }
    @Test fun streamsToTwoIndependentClientsAndReportsMetrics() {
        val stats = StreamStats()
        val server = MjpegServer("Test camera", stats)
        server.start()
        val first = Socket("127.0.0.1", 8080).apply { soTimeout = 3000 }
        val second = Socket("127.0.0.1", 8080).apply { soTimeout = 3000 }
        try {
            val jpeg = byteArrayOf(-1, -40, 1, 2, -1, -39)
            stats.rotationDegrees = 90; stats.encoded(12.0)
            server.publish(jpeg, 90)
            fun open(socket: Socket): MultipartReader {
                socket.getOutputStream().write("GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                assertEquals("HTTP/1.1 200 OK", line(input))
                var rotation = false
                while (true) {
                    val header = line(input); if (header.isEmpty()) break
                    if (header == "X-Rotation: 90") rotation = true
                }
                assertTrue(rotation)
                return MultipartReader(input, "multipart/x-mixed-replace; boundary=mirrorframe")
            }
            val a = open(first); val b = open(second)
            assertArrayEquals(jpeg, a.nextFrame()); assertArrayEquals(jpeg, b.nextFrame())
            assertEquals(90, a.rotationDegrees)
            // Publishing many frames never depends on whether either socket is being read.
            val start = System.nanoTime()
            repeat(100) { server.publish(jpeg, 90) }
            assertTrue(System.nanoTime() - start < 1_000_000_000L)
            Socket("127.0.0.1", 8080).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /status HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = BufferedInputStream(socket.getInputStream())
                assertEquals("HTTP/1.1 200 OK", line(input))
                while (line(input).isNotEmpty()) { /* headers */ }
                val status = JSONObject(input.readBytes().toString(Charsets.UTF_8))
                assertEquals(2, status.getInt("clients")); assertEquals(90, status.getInt("rotationDegrees"))
                assertEquals(12.0, status.getDouble("encodeMs"), .001)
                assertTrue(status.has("cameraFps")); assertTrue(status.has("sendFps"))
            }
        } finally { first.close(); second.close(); server.close() }
    }
}

