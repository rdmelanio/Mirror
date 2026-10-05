package com.mirror.app.phone

import com.mirror.app.BuildConfig
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MjpegServer(private val name: String, private val stats: StreamStats) {
    @Volatile var controls: ControlEndpoint? = null
    private val monitor = Object()
    private data class Frame(val jpeg: ByteArray, val rotation: Int)
    private val latest = AtomicReference<Frame?>()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap<Socket, Long>()
    private val streams = Semaphore(2)
    fun start() {
        val socket = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(8080)) }
        server = socket; running = true
        var sampledAt = System.nanoTime()
        watchdog.scheduleAtFixedRate({
            val now = System.nanoTime()
            stats.sample((now - sampledAt) / 1_000_000_000.0); sampledAt = now
            sockets.forEach { (client, lastWrite) ->
                if (now - lastWrite > TimeUnit.SECONDS.toNanos(15)) runCatching { client.close() }
            }
        }, 1, 1, TimeUnit.SECONDS)
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
    fun publish(jpeg: ByteArray, rotation: Int) {
        latest.set(Frame(jpeg, rotation))
        synchronized(monitor) { monitor.notifyAll() }
    }
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
                val output = BufferedOutputStream(client.getOutputStream(), 256 * 1024)
                fun response(code: String, type: String, body: String) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes); output.flush()
                }
                if (request.size < 2 || request[0] != "GET") { response("405 Method Not Allowed", "text/plain", "GET only"); return }
                when (request[1].substringBefore('?')) {
                    "/status" -> {
                        val streaming = running && latest.get() != null
                        val json = controls?.status() ?: JSONObject().put("controls", false).put("zoom", 1)
                            .put("minZoom", 1).put("maxZoom", 1).put("hasFlash", false).put("torch", false)
                        response("200 OK", "application/json", json.put("name", name)
                            .put("version", BuildConfig.VERSION_NAME).put("streaming", streaming)
                            .put("rotationDegrees", stats.rotationDegrees).put("cameraFps", stats.cameraFps)
                            .put("encodeMs", stats.encodeMs).put("sendFps", stats.sendFps)
                            .put("clients", stats.clients.get()).toString())
                    }
                    "/control" -> {
                        val endpoint = controls
                        if (endpoint == null) { response("503 Service Unavailable", "application/json", JSONObject().put("ok", false).put("error", "Camera is not ready").toString()); return }
                        try {
                            val query = request[1].substringAfter('?', "")
                            val parameters = query.split('&').filter { it.isNotEmpty() }.associate { part ->
                                java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                                    java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                            }
                            // Combined controls may include a full-resolution capture.
                            sockets[client] = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
                            val result = endpoint.control(parameters)
                            response(if (result.optBoolean("ok")) "200 OK" else "400 Bad Request", "application/json", result.toString())
                        } catch (_: Exception) { response("400 Bad Request", "application/json", JSONObject().put("ok", false).put("error", "Invalid control request").toString()) }
                    }
                    "/video" -> {
                        slot = streams.tryAcquire()
                        if (!slot) { response("503 Service Unavailable", "text/plain", "Two cameras viewers are already connected"); return }
                        stats.clients.incrementAndGet()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=mirrorframe\r\n" +
                            "X-Rotation: ${stats.rotationDegrees}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                        var sent: Frame? = null
                        var nextSend = 0L
                        while (running && !client.isClosed) {
                            val frame = synchronized(monitor) {
                                while (running && !client.isClosed) {
                                    val remaining = nextSend - System.nanoTime()
                                    if (latest.get() != null && latest.get() !== sent && remaining <= 0) break
                                    if (remaining > 0) TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                                    else monitor.wait(1000)
                                }
                                if (!running || client.isClosed) null else latest.get()
                            } ?: break
                            val sentAt = System.nanoTime()
                            output.write("--mirrorframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\nX-Rotation: ${frame.rotation}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(frame.jpeg); output.write(byteArrayOf(13, 10)); output.flush()
                            sent = frame; nextSend = sentAt + 33_333_333L
                            stats.sent()
                            sockets[client] = System.nanoTime()
                        }
                    }
                    else -> response("404 Not Found", "text/plain", "Use /video, /status or /control")
                }
            }
        } catch (_: Exception) { /* Disconnects are normal, including sleeping viewers. */ }
        finally { if (slot) { streams.release(); stats.clients.decrementAndGet() }; sockets.remove(socket); runCatching { socket.close() } }
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
        synchronized(monitor) { latest.set(null); monitor.notifyAll() }
        runCatching { server?.close() }; server = null
        sockets.keys.forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow(); watchdog.shutdownNow()
    }
}

