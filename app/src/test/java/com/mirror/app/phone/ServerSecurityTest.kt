package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import org.json.JSONObject

class ServerSecurityTest {
    private var port = 0
    private fun request(path: String, header: String = "", method: String = "GET", body: String = ""): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: localhost\r\n$header" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    @Test fun endpointsRequireHeaderTokenAndBasicCannotControl() {
        val security = PairingAuthority()
        val token = security.pair(security.showCode()!!, "id", "TV").token!!
        var blocked = 0
        val server = MjpegServer("Camera", StreamStats(), security, InetAddress.getByName("127.0.0.1"),
            blocked = { blocked++ }, allowAddress = { it.isLoopbackAddress }, listenPort = 0)
        server.start(); port = server.port
        try {
            val hello = request("/hello")
            assertTrue(hello.startsWith("HTTP/1.1 200")); assertTrue(hello.contains(security.cameraId))
            assertFalse(hello.contains("cameraFps")); assertFalse(hello.contains("hasFlash"))
            listOf("/status", "/control?torch=on", "/video", "/anything", "/status?token=$token").forEach { path ->
                val response = request(path)
                assertTrue(response.startsWith("HTTP/1.1 401")); assertEquals("", response.substringAfter("\r\n\r\n"))
            }
            assertEquals(1, blocked)
            assertTrue(request("/status", "X-Mirror-Token: $token\r\n").startsWith("HTTP/1.1 200"))
            assertTrue(request("/status", "X-Mirror-Token: invalid\r\n").startsWith("HTTP/1.1 401"))
            security.setBrowserPassword("test-password")
            val basic = "Authorization: Basic " + Base64.getEncoder().encodeToString("mirror:test-password".toByteArray()) + "\r\n"
            assertTrue(request("/status", basic).startsWith("HTTP/1.1 200"))
            assertTrue(request("/control?torch=on", basic).startsWith("HTTP/1.1 401"))
            security.remove(security.list().single().hash)
            assertTrue(request("/status", "X-Mirror-Token: $token\r\n").startsWith("HTTP/1.1 401"))
        } finally { server.close() }
    }
    @Test fun pairingHttpReturnsTokenAndLocksOutBruteForce() {
        val security = PairingAuthority(); val code = security.showCode()!!
        val server = MjpegServer("Camera", StreamStats(), security, InetAddress.getByName("127.0.0.1"), allowAddress = { it.isLoopbackAddress }, listenPort = 0)
        server.start(); port = server.port
        try {
            fun body(value: String) = JSONObject().put("code", value).put("deviceId", "id").put("deviceName", "TV").toString()
            val result = request("/pair", method = "POST", body = body(code))
            assertTrue(result.startsWith("HTTP/1.1 200"))
            val token = JSONObject(result.substringAfter("\r\n\r\n")).getString("token")
            assertNotNull(security.authenticate(token))
            val wrong = if (code == "000000") "000001" else "000000"
            repeat(4) { assertTrue(request("/pair", method = "POST", body = body(wrong)).startsWith("HTTP/1.1 403")) }
            assertTrue(request("/pair", method = "POST", body = body(wrong)).startsWith("HTTP/1.1 429"))
            assertTrue(request("/pair", method = "POST", body = body(code)).contains("Too many attempts"))
        } finally { server.close() }
    }
    @Test fun privateAddressesOnly() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1", "fd00::1", "fe80::1").forEach {
            assertTrue(it, MjpegServer.isLanAddress(InetAddress.getByName(it)))
        }
        listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "172.15.1.1", "172.32.1.1", "192.169.1.1", "::1", "2001:4860:4860::8888").forEach {
            assertFalse(it, MjpegServer.isLanAddress(InetAddress.getByName(it)))
        }
    }
    @Test fun removalClosesAnAlreadyOpenStreamWithoutWaitingForAnotherFrame() {
        val security = PairingAuthority()
        val token = security.pair(security.showCode()!!, "id", "TV").token!!
        val server = MjpegServer("Camera", StreamStats(), security, InetAddress.getByName("127.0.0.1"), allowAddress = { it.isLoopbackAddress }, listenPort = 0)
        server.start(); port = server.port
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /video HTTP/1.1\r\nHost: localhost\r\nX-Mirror-Token: $token\r\n\r\n".toByteArray())
                val input = socket.getInputStream().bufferedReader()
                assertEquals("HTTP/1.1 200 OK", input.readLine())
                while (input.readLine().isNotEmpty()) {}
                security.remove(security.list().single().hash)
                assertEquals(-1, input.read())
            }
        } finally { server.close() }
    }
}
