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
    private val ownership = AirOwnership()
    private val rate = AirInferenceRate()
    @Volatile private var armedMode=false
    @Volatile private var intervalMs=333L
    @Volatile private var feedback: AirFeedbackServer?=null
    private var feedbackAddress: String?=null
    private var failed = false
    private var wasEnabled = false
    init { AirRemoteTv.init(context) }
    fun needsFrame(): Boolean = !closed && enabled(context) && !busy.get() && SystemClock.elapsedRealtime()-lastFrame>=intervalMs
    @Synchronized fun offer(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (closed || !enabled(context) || now - lastFrame < intervalMs || !busy.compareAndSet(false, true)) return
        lastFrame = now
        worker.execute {
            var inferenceStart=0L
            try {
                if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 250 || failed) return@execute
                inferenceStart=SystemClock.elapsedRealtime()
                AirRemoteTv.connect(context)
                if (recognizer == null) recognizer = GestureRecognizer.createFromOptions(context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(4)
                        .setMinHandDetectionConfidence(.65f).setMinHandPresenceConfidence(.65f).setMinTrackingConfidence(.65f).build())
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@execute
                val scale = 480f / maxOf(decoded.width, decoded.height)
                val bitmap = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val image = BitmapImageBuilder(bitmap).build()
                    try {
                        val result = recognizer!!.recognizeForVideo(image, now)
                        if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 500) { gestures.reset();ownership.reset();armedMode=false;return@execute }
                        val prefs=AirRemoteTv.prefs(context)
                        gestures.sensitivity=prefs.getInt("sensitivity",50)
                        if(!AirRemoteTv.connected) { gestures.reset();ownership.reset();armedMode=false }
                        val hands=result.landmarks().mapIndexed { index, hand ->
                            val category=result.gestures().getOrNull(index)?.maxByOrNull { it.score() }
                            val palm=listOf(0,5,9,13,17).map { hand[it] }
                            val rawX=palm.map { it.x() }.average().toFloat()
                            val x=if(prefs.getBoolean("mirror",true)) 1f-rawX else rawX
                            val y=palm.map { it.y() }.average().toFloat()
                            val size=kotlin.math.hypot(hand[0].x()-hand[9].x(),hand[0].y()-hand[9].y())
                            AirOwnership.Hand(x,y,size,category?.categoryName().orEmpty(),category?.score() ?: 0f)
                        }
                        if(AirRemoteTv.connected) {
                            val selection=ownership.update(now,hands)
                            when {
                                selection.lost -> { gestures.reset();armedMode=false }
                                selection.ambiguous -> gestures.pause(now)
                                selection.claimed -> {
                                    val hand=selection.hand!!;gestures.claim(now,hand.x,hand.y)
                                    armedMode=true;feedback?.post("ACTIVE")
                                }
                                selection.hand!=null -> {
                                    val hand=selection.hand
                                    val command=gestures.update(now,hand.gesture,hand.confidence,hand.x,hand.y)
                                    if(!gestures.armed) { ownership.reset();armedMode=false }
                                    else command?.let { AirRemoteTv.send(it) }
                                }
                            }
                        }
                        recognition=when {
                            !AirRemoteTv.connected -> "Waiting for TV · standby"
                            armedMode -> "● Active · tracked hand only"
                            else -> "○ Standby · hold palm still 2 seconds"
                        } + " · ${rate.latencyMs.toInt()} ms · ${1000/intervalMs} fps"

                    } finally { image.close() }
                } finally { bitmap.recycle(); if (bitmap !== decoded) decoded.recycle() }
            } catch (e: Exception) { gestures.reset();ownership.reset();armedMode=false; failed = true; recognition = "Recognition unavailable: ${e.message}. Toggle Air Remote to retry." }
            catch (e: LinkageError) { failed = true; recognition = "Recognition native library unavailable on this phone" }
            finally {
                if(inferenceStart>0) { rate.measured(SystemClock.elapsedRealtime()-inferenceStart);intervalMs=rate.interval(armedMode) }
                busy.set(false)
            }
        }
    }
    @Synchronized fun refresh() {
        if (closed) return
        val current = enabled(context)
        if (current) {
            AirRemoteTv.connect(context)
            AirRemoteTv.onSent={ command -> feedback?.post("SENT_${command.name}") }
            val address=com.mirror.app.core.LocalNetwork.address(context)
            if(address!=feedbackAddress) {
                feedback?.close();feedback=null;feedbackAddress=null
                if(address!=null) runCatching {
                    feedback=AirFeedbackServer(PhoneSecurity.get(context),java.net.InetAddress.getByName(address), { peer ->
                        peer.hostAddress==AirRemoteTv.prefs(context).getString("host","") && MjpegServer.isLanAddress(peer)
                    });feedbackAddress=address
                }.onFailure { recognition="Feedback unavailable: ${it.message}" }
            }
        }
        if (wasEnabled && !current) {
            AirRemoteTv.stop();AirRemoteTv.onSent=null;feedback?.close();feedback=null;feedbackAddress=null; recognition = "Disabled"
            worker.execute { gestures.reset();ownership.reset();armedMode=false;intervalMs=333; recognizer?.close(); recognizer = null; failed = false }
        }
        wasEnabled = current
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true;AirRemoteTv.onSent=null;feedback?.close();feedback=null; AirRemoteTv.stop(); recognition = "Camera stopped · disarmed"
        worker.execute { recognizer?.close(); recognizer = null; gestures.reset() }; worker.shutdown()
    }
    companion object {
        @Volatile var recognition = "Camera not started"
        private var lastStartAttempt=0L
        fun ensureCamera(activity: android.app.Activity) {
            if(!enabled(activity) || CameraService.active || activity.isFinishing || !activity.hasWindowFocus()) return
            if(activity.checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val now=SystemClock.elapsedRealtime();if(now-lastStartAttempt<30_000) return;lastStartAttempt=now
            val prefs=activity.getSharedPreferences("mirror",Context.MODE_PRIVATE)
            runCatching { activity.startForegroundService(android.content.Intent(activity,CameraService::class.java)
                .putExtra("front",prefs.getBoolean("phoneFront",false)).putExtra("fullHd",prefs.getBoolean("phoneFullHd",false))) }
                .onFailure { recognition="Android requires reopening the camera from Mirror: ${it.message}" }
        }
        fun enabled(context: Context) = AirRemoteTv.prefs(context).getBoolean("enabled", false)
    }
}
