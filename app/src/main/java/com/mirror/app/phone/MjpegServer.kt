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

class MjpegServer(private val name: String, private val stats: StreamStats,
                  private val security: PairingAuthority, private val bindAddress: java.net.InetAddress,
                  private val blocked: (String) -> Unit = {}, private val viewersChanged: (List<String>) -> Unit = {},
                  private val allowAddress: (java.net.InetAddress) -> Boolean = ::isLanAddress) {
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
    private data class Viewer(val hash: String?, val name: String)
    private val authorized = ConcurrentHashMap<Socket, String>()
    private val viewers = ConcurrentHashMap<Socket, Viewer>()
    private val blockedAt = ConcurrentHashMap<String, Long>()
    fun viewerNames(): List<String> = viewers.values.map { it.name }
    fun clearFrames() { synchronized(monitor) { latest.set(null); monitor.notifyAll() } }
    companion object {
        fun isLanAddress(address: java.net.InetAddress): Boolean {
            val b = address.address
            return if (b.size == 4) {
                val a = b[0].toInt() and 255; val second = b[1].toInt() and 255
                a == 10 || a == 172 && second in 16..31 || a == 192 && second == 168
            } else b.size == 16 && ((b[0].toInt() and 254) == 252 || address.isLinkLocalAddress)
        }
    }
    fun start() {
        val socket = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(bindAddress, 8080)) }
        server = socket; running = true
        security.onRevoked = { hash ->
            authorized.forEach { (client, tokenHash) -> if (tokenHash == hash) runCatching { client.close() } }
            synchronized(monitor) { monitor.notifyAll() }
        }
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
                    if (!allowAddress(client.inetAddress) || sockets.size >= 4) { client.close(); continue }
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
                val headers = mutableMapOf<String, String>()
                var size = 0
                while (true) {
                    val header = readLine(input); size += header.length
                    require(size < 32768)
                    if (header.isEmpty()) break
                    val key = header.substringBefore(':').lowercase(java.util.Locale.ROOT)
                    require(':' in header && !headers.containsKey(key))
                    headers[key] = header.substringAfter(':').trim()
                }
                val output = BufferedOutputStream(client.getOutputStream(), 256 * 1024)
                fun response(code: String, type: String, body: String) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes); output.flush()
                }
                if (request.size != 3) { response("400 Bad Request", "text/plain", "Invalid request"); return }
                val path = request[1].substringBefore('?')
                if (path == "/hello" && request[0] == "GET") {
                    response("200 OK", "application/json", JSONObject().put("app", "mirror").put("cameraId", security.cameraId)
                        .put("name", name).put("version", BuildConfig.VERSION_NAME).put("requiresPairing", true).toString()); return
                }
                if (path == "/pair" && request[0] == "POST") {
                    try {
                        require(!headers.containsKey("transfer-encoding"))
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        require(length in 1..4096)
                        val body = ByteArray(length); var read = 0
                        while (read < length) { val n = input.read(body, read, length - read); require(n > 0); read += n }
                        val json = JSONObject(String(body, Charsets.UTF_8))
                        val result = security.pair(json.optString("code"), json.optString("deviceId"), json.optString("deviceName"))
                        val code = when (result.status) { 200 -> "200 OK"; 429 -> "429 Too Many Requests"; 403 -> "403 Forbidden"; else -> "400 Bad Request" }
                        val value = if (result.token != null) JSONObject().put("token", result.token) else JSONObject().put("error", result.error)
                        response(code, "application/json", value.toString())
                    } catch (_: Exception) { response("400 Bad Request", "application/json", "{}"); }
                    return
                }
                val device = headers["x-mirror-token"]?.let { security.authenticate(it) }
                val browser = device == null && request[0] == "GET" && path in listOf("/video", "/status") &&
                    security.browserAuth(headers["authorization"].orEmpty())
                if (device == null && !browser) {
                    if (path in listOf("/video", "/status", "/control")) {
                        val ip = client.inetAddress.hostAddress.orEmpty(); val now = System.nanoTime()
                        synchronized(blockedAt) {
                            val previous = blockedAt[ip]
                            if (previous == null || now - previous >= TimeUnit.MINUTES.toNanos(1)) {
                                if (blockedAt.size >= 256) blockedAt.entries.removeIf { now - it.value >= TimeUnit.MINUTES.toNanos(1) }
                                if (blockedAt.size < 256) { blockedAt[ip] = now; blocked(ip) }
                            }
                        }
                    }
                    output.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Mirror\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.flush(); return
                }
                if (device != null) {
                    authorized[client] = device.hash
                    if (!security.isPaired(device.hash)) { response("401 Unauthorized", "text/plain", ""); return }
                }
                if (request[0] != "GET") { response("405 Method Not Allowed", "text/plain", "GET only"); return }
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
                        viewers[client] = Viewer(device?.hash, device?.name ?: "Browser")
                        viewersChanged(viewerNames())
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=mirrorframe\r\n" +
                            "X-Rotation: ${stats.rotationDegrees}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                        var sent: Frame? = null
                        var nextSend = 0L
                        var nextSeen = 0L
                        while (running && !client.isClosed) {
                            if (device != null && !security.isPaired(device.hash) || browser && !security.browserEnabled()) break
                            val frame = synchronized(monitor) {
                                while (running && !client.isClosed) {
                                    if (device != null && !security.isPaired(device.hash) || browser && !security.browserEnabled()) break
                                    val remaining = nextSend - System.nanoTime()
                                    if (latest.get() != null && latest.get() !== sent && remaining <= 0) break
                                    if (remaining > 0) TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                                    else monitor.wait(1000)
                                }
                                if (!running || client.isClosed || device != null && !security.isPaired(device.hash) ||
                                    browser && !security.browserEnabled()) null else latest.get()
                            } ?: break
                            val sentAt = System.nanoTime()
                            output.write("--mirrorframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\nX-Rotation: ${frame.rotation}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(frame.jpeg); output.write(byteArrayOf(13, 10)); output.flush()
                            sent = frame; nextSend = sentAt + 33_333_333L
                            if (device != null && sentAt >= nextSeen) {
                                security.authenticate(headers["x-mirror-token"].orEmpty()); nextSeen = sentAt + TimeUnit.MINUTES.toNanos(1)
                            }
                            stats.sent()
                            sockets[client] = System.nanoTime()
                        }
                    }
                    else -> response("404 Not Found", "text/plain", "Use /video, /status or /control")
                }
            }
        } catch (_: Exception) { /* Disconnects are normal, including sleeping viewers. */ }
        finally {
            if (slot) { streams.release(); stats.clients.decrementAndGet(); viewers.remove(socket); viewersChanged(viewerNames()) }
            authorized.remove(socket); sockets.remove(socket); runCatching { socket.close() }
        }
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
        security.onRevoked = null
        synchronized(monitor) { latest.set(null); monitor.notifyAll() }
        runCatching { server?.close() }; server = null
        sockets.keys.forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow(); watchdog.shutdownNow()
    }
}


