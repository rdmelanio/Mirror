package com.mirror.app.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One in-flight sampled JPEG, sharing CameraService's upright immutable frame. No ImageProxy retention. */
internal class AirRemote(private val context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private var lastFrame = 0L
    @Volatile private var closed = false
    private var recognizer: GestureRecognizer? = null
    private val gestures = AirGestures()
    private var failed = false
    private var wasEnabled = false
    init { AirRemoteTv.init(context) }
    @Synchronized fun offer(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (closed || !enabled(context) || now - lastFrame < 111 || !busy.compareAndSet(false, true)) return
        lastFrame = now
        worker.execute {
            try {
                if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 250 || failed) return@execute
                AirRemoteTv.connect(context)
                if (recognizer == null) recognizer = GestureRecognizer.createFromOptions(context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(1)
                        .setMinHandDetectionConfidence(.65f).setMinHandPresenceConfidence(.65f).setMinTrackingConfidence(.65f).build())
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@execute
                val scale = 480f / maxOf(decoded.width, decoded.height)
                val bitmap = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val image = BitmapImageBuilder(bitmap).build()
                    try {
                        val result = recognizer!!.recognizeForVideo(image, now)
                        if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 500) { gestures.reset(); return@execute }
                        val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
                        val hand = result.landmarks().firstOrNull()
                        val palm = hand?.filterIndexed { i, _ -> i in listOf(0, 5, 9, 13, 17) }
                        val rawX = palm?.map { it.x() }?.average()?.toFloat() ?: 0f
                        val y = palm?.map { it.y() }?.average()?.toFloat() ?: 0f
                        val prefs = AirRemoteTv.prefs(context)
                        val x = if (prefs.getBoolean("mirror", true)) 1f - rawX else rawX
                        gestures.sensitivity = prefs.getInt("sensitivity", 50)
                        if (!AirRemoteTv.connected) gestures.reset()
                        val command = gestures.update(now, category?.categoryName().orEmpty(), category?.score() ?: 0f, x, y)
                        if (AirRemoteTv.connected) command?.let { AirRemoteTv.send(it) } else gestures.reset()
                        recognition = if (!AirRemoteTv.connected) "Waiting for TV · disarmed" else "${if (gestures.armed) "● Armed" else "○ Hold palm still to arm"} · ${category?.categoryName() ?: "No hand"}"
                    } finally { image.close() }
                } finally { bitmap.recycle(); if (bitmap !== decoded) decoded.recycle() }
            } catch (e: Exception) { gestures.reset(); failed = true; recognition = "Recognition unavailable: ${e.message}. Toggle Air Remote to retry." }
            catch (e: LinkageError) { failed = true; recognition = "Recognition native library unavailable on this phone" }
            finally { busy.set(false) }
        }
    }
    @Synchronized fun refresh() {
        if (closed) return
        val current = enabled(context)
        if (current) AirRemoteTv.connect(context)
        if (wasEnabled && !current) {
            AirRemoteTv.stop(); recognition = "Disabled"
            worker.execute { gestures.reset(); recognizer?.close(); recognizer = null; failed = false }
        }
        wasEnabled = current
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; AirRemoteTv.stop(); recognition = "Camera stopped · disarmed"
        worker.execute { recognizer?.close(); recognizer = null; gestures.reset() }; worker.shutdown()
    }
    companion object {
        @Volatile var recognition = "Camera not started"
        fun enabled(context: Context) = AirRemoteTv.prefs(context).getBoolean("enabled", false)
    }
}
