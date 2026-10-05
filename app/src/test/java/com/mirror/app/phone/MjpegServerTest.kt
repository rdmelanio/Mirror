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
