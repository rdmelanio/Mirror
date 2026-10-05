package com.mirror.app.tv

import com.mirror.app.core.MultipartReader
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class MjpegClient(private val address: String, private val frame: (VideoFrame) -> Unit,
                  private val status: (String) -> Unit, private val ready: () -> Boolean) {
    private data class Jpeg(val bytes: ByteArray, val rotation: Int)
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    private var decoder: Thread? = null
    private val monitor = Object()
    private var latest: Jpeg? = null
    private val pool = BitmapPool()
    val receivedFrames = AtomicInteger()
    fun start() {
        running = true
        decoder = Thread({
            while (running) {
                val jpeg = synchronized(monitor) {
                    while (running && (latest == null || !ready())) monitor.wait(16)
                    if (!running) null else latest.also { latest = null }
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
                status("Connecting...")
                var http: HttpURLConnection? = null
                try {
                    http = URL(address).openConnection() as HttpURLConnection
                    connection = http
                    http.connectTimeout = 5000; http.readTimeout = 5000; http.useCaches = false
                    http.setRequestProperty("Accept", "multipart/x-mixed-replace")
                    if (!running) break
                    check(http.responseCode == 200) { "HTTP ${http.responseCode}" }
                    val rotation = http.getHeaderField("X-Rotation")?.toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) } ?: 0
                    var connected = false
                    BufferedInputStream(http.inputStream, 256 * 1024).use { stream ->
                        val reader = MultipartReader(stream, http.contentType)
                        while (running) {
                            val jpeg = reader.nextFrame()
                            receivedFrames.incrementAndGet()
                            synchronized(monitor) { latest = Jpeg(jpeg, reader.rotationDegrees ?: rotation); monitor.notifyAll() }
                            if (!connected) { connected = true; status("") }
                        }
                    }
                } catch (_: Exception) {
                    if (running) status("Can't reach camera - open Mirror on your phone and press Start")
                } finally {
                    http?.disconnect(); connection = null
                    synchronized(monitor) { latest = null }
                }
                if (running) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }, "Mirror-mjpeg-read").apply { start() }
    }
    fun stop() {
        running = false
        synchronized(monitor) { latest = null; monitor.notifyAll() }
        connection?.disconnect(); worker?.interrupt(); pool.close()
    }
}
