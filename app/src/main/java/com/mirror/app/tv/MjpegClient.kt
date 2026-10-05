package com.mirror.app.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mirror.app.core.MultipartReader
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class MjpegClient(private val address: String, private val frame: (Bitmap) -> Unit, private val status: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    fun start() {
        running = true
        worker = Thread({
            while (running) {
                status("Connecting...")
                try {
                    val http = URL(address).openConnection() as HttpURLConnection
                    connection = http
                    http.connectTimeout = 5000; http.readTimeout = 5000; http.useCaches = false
                    http.setRequestProperty("Accept", "multipart/x-mixed-replace")
                    if (!running) break
                    check(http.responseCode == 200) { "HTTP ${http.responseCode}" }
                    BufferedInputStream(http.inputStream, 64 * 1024).use { stream ->
                        val reader = MultipartReader(stream, http.contentType)
                        while (running) {
                            val jpeg = reader.nextFrame()
                            // Protect the 32-bit TV from an unexpectedly huge external-camera frame.
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                            check(bounds.outWidth > 0 && bounds.outHeight > 0)
                            var sample = 1
                            while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2
                            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: continue
                            if (running) frame(bitmap) else bitmap.recycle()
                        }
                    }
                } catch (_: Exception) {
                    if (running) status("Can't reach camera - open Mirror on your phone and press Start")
                } finally { connection?.disconnect(); connection = null }
                if (running) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }, "Mirror-mjpeg-client").apply { start() }
    }
    fun stop() { running = false; connection?.disconnect(); worker?.interrupt() }
}
