package com.mirror.app.phone

import com.mirror.app.BuildConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MjpegServer(private val name: String) {
    private val monitor = Object()
    private var latest: ByteArray? = null
    private var sequence = 0L
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap<Socket, Long>()
    private val streams = Semaphore(2)
    fun start() {
        val socket = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(8080)) }
        server = socket; running = true
        watchdog.scheduleAtFixedRate({
            val now = System.nanoTime()
            sockets.forEach { (client, lastWrite) ->
                if (now - lastWrite > TimeUnit.SECONDS.toNanos(15)) runCatching { client.close() }
            }
        }, 5, 5, TimeUnit.SECONDS)
        Thread({
            while (running) {
                try {
                    val client = socket.accept().apply { soTimeout = 5000; tcpNoDelay = true }
                    if (sockets.size >= 4) { client.close(); continue }
                    sockets[client] = System.nanoTime()
                    workers.execute { handle(client) }
                } catch (_: Exception) { if (running) close() }
            }
        }, "Mirror-http-accept").start()
    }
    fun publish(jpeg: ByteArray) { synchronized(monitor) { latest = jpeg; sequence++; monitor.notifyAll() } }
    private fun handle(socket: Socket) {
        var slot = false
        try {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val request = readLine(input).split(' ')
                var size = 0
                while (true) {
                    val header = readLine(input); size += header.length
                    require(size < 32768)
                    if (header.isEmpty()) break
                }
                val output = client.getOutputStream()
                fun response(code: String, type: String, body: String) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes); output.flush()
                }
                if (request.size < 2 || request[0] != "GET") { response("405 Method Not Allowed", "text/plain", "GET only"); return }
                when (request[1].substringBefore('?')) {
                    "/status" -> {
                        val streaming = synchronized(monitor) { running && latest != null }
                        response("200 OK", "application/json", JSONObject().put("name", name)
                            .put("version", BuildConfig.VERSION_NAME).put("streaming", streaming).toString())
                    }
                    "/video" -> {
                        slot = streams.tryAcquire()
                        if (!slot) { response("503 Service Unavailable", "text/plain", "Two cameras viewers are already connected"); return }
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=mirrorframe\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                        var sent = -1L
                        while (running && !client.isClosed) {
                            val frame = synchronized(monitor) {
                                while (running && sequence == sent) monitor.wait(1000)
                                if (!running) null else latest?.also { sent = sequence }
                            }
                            if (frame == null) { synchronized(monitor) { monitor.wait(1000) }; continue }
                            output.write("--mirrorframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(frame); output.write(byteArrayOf(13, 10)); output.flush()
                            sockets[client] = System.nanoTime()
                        }
                    }
                    else -> response("404 Not Found", "text/plain", "Use /video or /status")
                }
            }
        } catch (_: Exception) { /* Disconnects are normal, including sleeping viewers. */ }
        finally { if (slot) streams.release(); sockets.remove(socket); runCatching { socket.close() } }
    }
    private fun readLine(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 8192) {
            val b = input.read(); require(b >= 0)
            if (b == 10) return out.toString("US-ASCII").removeSuffix("\r")
            out.write(b)
        }
        error("Header too long")
    }
    fun close() {
        running = false
        synchronized(monitor) { latest = null; monitor.notifyAll() }
        runCatching { server?.close() }; server = null
        sockets.keys.forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow(); watchdog.shutdownNow()
    }
}
