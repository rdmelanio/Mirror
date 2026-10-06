package com.mirror.app.tv

import com.mirror.app.core.MultipartReader
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class MjpegClient(private val address: String, private val frame: (VideoFrame) -> Unit,
                  private val status: (String) -> Unit, private val ready: () -> Boolean,
                  private val auth: SourceAuth = SourceAuth(), private val unauthorized: () -> Unit = {},
                  private val delayStatus: (String) -> Unit = {}) {
    private data class Jpeg(val bytes: ByteArray, val rotation: Int)
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    private var decoder: Thread? = null
    private val monitor = Object()
    private var latest: Jpeg? = null
    private val pool = BitmapPool()
    private val buffer = DelayedFrames()
    private var delaySeconds = 0
    private var delayMessage = ""
    private var playing = false
    fun configureDelay(seconds: Int) {
        synchronized(monitor) {
            if (delaySeconds == seconds) return
            delaySeconds = seconds; buffer.clear(); latest = null; playing = false; delayMessage = "unset"
            monitor.notifyAll()
        }
    }
    private fun progress(value: String) {
        if (value != delayMessage) { delayMessage = value; delayStatus(value) }
    }
    val receivedFrames = AtomicInteger()
    fun start() {
        running = true
        decoder = Thread({
            while (running) {
                val jpeg = synchronized(monitor) {
                    var selected: Jpeg? = null
                    while (running && selected == null) {
                        if (delaySeconds == 0) {
                            progress("")
                            if (latest != null && ready()) { selected = latest; latest = null }
                        } else {
                            val now = System.nanoTime() / 1_000_000
                            if (ready()) buffer.take(now, delaySeconds * 1000L)?.let {
                                selected = Jpeg(it.bytes, it.rotation); playing = true
                            }
                            progress(if (playing) "Delayed ${delaySeconds}s" else "Recording... ready in ${buffer.remainingSeconds(now, delaySeconds * 1000L)} s")
                        }
                        if (selected == null) monitor.wait(16)
                    }
                    selected
                } ?: break
                try {
                    val bitmap = pool.decode(jpeg.bytes) ?: continue
                    val decoded = VideoFrame(bitmap, jpeg.rotation, pool)
                    if (running) frame(decoded) else decoded.release()
                } catch (_: Exception) { /* Skip a malformed external-camera frame. */ }
            }
        }, "Mirror-jpeg-decode").apply { start() }
        worker = Thread({
            while (running) {
                status(if (auth.token != null) "Waking camera..." else "Connecting...")
                var http: HttpURLConnection? = null
                try {
                    http = URL(address).openConnection() as HttpURLConnection
                    connection = http
                    http.connectTimeout = 5000; http.readTimeout = 5000; http.useCaches = false
                    auth.apply(http)
                    http.setRequestProperty("Accept", "multipart/x-mixed-replace")
                    if (!running) break
                    if (http.responseCode == 401 && auth.token != null) { running = false; unauthorized(); break }
                    check(http.responseCode == 200) { "HTTP ${http.responseCode}" }
                    val rotation = http.getHeaderField("X-Rotation")?.toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) } ?: 0
                    var connected = false
                    BufferedInputStream(http.inputStream, 256 * 1024).use { stream ->
                        val reader = MultipartReader(stream, http.contentType)
                        while (running) {
                            val jpeg = reader.nextFrame()
                            receivedFrames.incrementAndGet()
                            synchronized(monitor) {
                                val degrees = reader.rotationDegrees ?: rotation
                                if (delaySeconds == 0) latest = Jpeg(jpeg, degrees)
                                else buffer.add(DelayedFrames.Frame(jpeg, degrees, System.nanoTime() / 1_000_000))
                                monitor.notifyAll()
                            }
                            if (!connected) { connected = true; status("") }
                        }
                    }
                } catch (_: Exception) {
                    if (running) status("Can't reach camera - open Mirror on your phone and press Start")
                } finally {
                    http?.disconnect(); connection = null
                    synchronized(monitor) { latest = null; buffer.clear(); playing = false }
                }
                if (running) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }, "Mirror-mjpeg-read").apply { start() }
    }
    fun stop() {
        running = false
        synchronized(monitor) { latest = null; buffer.clear(); monitor.notifyAll() }
        connection?.disconnect(); worker?.interrupt(); pool.close()
    }
}

