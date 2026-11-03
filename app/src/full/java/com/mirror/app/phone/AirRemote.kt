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
    private var lastResult = 0L
    private var observedFps = 0.0
    @Volatile private var closed = false
    private var recognizer: GestureRecognizer? = null
    private val session = AirControlSession(
        { if(!calibration) feedback?.post("ACTIVE") },
        { if(!calibration) AirRemoteTv.send(it) })
    private val gestures get() = session.gestures
    private val ownership get() = session.ownership
    private val rate = AirInferenceRate()
    @Volatile private var armedMode=false
    @Volatile private var intervalMs=333L
    @Volatile private var feedback: AirFeedbackServer?=null
    private var feedbackAddress: String?=null
    private val health = AirRecognitionHealth({ SystemClock.elapsedRealtime() }) {
        gestures.reset();ownership.reset();armedMode=false;handDetected=false
        recognition="Recognition error · ${healthMessage()}"
    }
    private fun healthMessage():String=health.lastError
    private var wasEnabled = false
    init { AirRemoteTv.init(context) }
    fun needsFrame(): Boolean = !closed && enabled(context) && !busy.get() && health.ready(SystemClock.elapsedRealtime()) && SystemClock.elapsedRealtime()-lastFrame>=intervalMs
    @Synchronized fun offer(jpeg: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (closed || !enabled(context) || now - lastFrame < intervalMs || !health.ready(now) || !busy.compareAndSet(false, true)) return
        lastFrame = now
        worker.execute {
            var inferenceStart=0L
            try {
                if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 250 || !health.ready(now)) return@execute
                health.run(now) frame@{
                inferenceStart=SystemClock.elapsedRealtime()
                inferenceState="Initializing model"
                AirRemoteTv.connect(context)
                if (recognizer == null) try { recognizer = GestureRecognizer.createFromOptions(context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(4)
                        .setMinHandDetectionConfidence(.65f).setMinHandPresenceConfidence(.65f).setMinTrackingConfidence(.65f).build())
                } catch(e:IllegalArgumentException) { throw AirModelUnavailable(e) }
                inferenceState="Model ready · processing"
                val decodeStart=SystemClock.elapsedRealtime()
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: throw IllegalArgumentException("Camera JPEG could not be decoded")
                try {
                val scale = minOf(1f, 320f / maxOf(decoded.width, decoded.height))
                val bitmap = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val image = BitmapImageBuilder(bitmap).build()
                    try {
                        val recognizeStart=SystemClock.elapsedRealtime()
                        val result = recognizer!!.recognizeForVideo(image, now)
                        if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 1200) {
                            gestures.reset();ownership.reset();armedMode=false
                            recognition=if(closed || !enabled(context)) "Disabled · disarmed" else "Standby · inference too slow; sampling reduced"
                            return@frame
                        }
                        val completed=SystemClock.elapsedRealtime()
                        if(lastResult>0) observedFps=1000.0/(completed-lastResult).coerceAtLeast(1)
                        lastResult=completed
                        lastInference=completed
                        val names=result.gestures().mapNotNull { it.maxByOrNull { c -> c.score() } }
                        handDetected=result.landmarks().isNotEmpty()
                        detectedGesture=names.joinToString { "${it.categoryName()} ${ (it.score()*100).toInt()}%" }.ifEmpty { "None" }
                        val prefs=AirRemoteTv.prefs(context)
                        val calibrating=calibration
                        val confidence=AirConfidence.fraction(prefs.getInt("confidence",65))
                        val hands=result.landmarks().mapIndexed { index, hand ->
                            val category=result.gestures().getOrNull(index)?.maxByOrNull { it.score() }
                            val palm=listOf(0,5,9,13,17).map { hand[it] }
                            val rawX=palm.map { it.x() }.average().toFloat()
                            val x=if(prefs.getBoolean("mirror",true)) 1f-rawX else rawX
                            val y=palm.map { it.y() }.average().toFloat()
                            val size=kotlin.math.hypot(hand[0].x()-hand[9].x(),hand[0].y()-hand[9].y())
                            AirOwnership.Hand(x,y,size,category?.categoryName().orEmpty(),category?.score() ?: 0f)
                        }
                        session.update(now,hands,AirRemoteTv.connected,calibrating,intervalMs,confidence,prefs.getInt("sensitivity",50))
                        armedMode=gestures.armed && ownership.claimed
                        session.accepted?.let { intendedCommand=it.name }
                        calibrationDetail="${gestures.movement}\nIntended command: $intendedCommand · ${if(calibrating) "CALIBRATION: no transmission" else "live"}"
                        inferenceState="Model ready · %.1f fps · %d ms (decode/resize %d · inference %d)".format(java.util.Locale.US,observedFps,completed-inferenceStart,recognizeStart-decodeStart,completed-recognizeStart)
                        recognition=session.status + " · %d ms · %.1f fps".format(java.util.Locale.US,rate.latencyMs.toInt(),observedFps)

                    } finally { image.close() }
                } finally { if(bitmap !== decoded) bitmap.recycle() }
                } finally { decoded.recycle() }
                }
                recoveryStatus=health.lastError
            } finally {
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
            AirRemoteTv.onSent={ command -> if(!calibration) feedback?.post("SENT_${command.name}") }
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
            AirRemoteTv.stop();AirRemoteTv.onSent=null;feedback?.close();feedback=null;feedbackAddress=null; recognition = "Disabled"; inferenceState="Disabled";handDetected=false;detectedGesture="None"
            worker.execute { gestures.reset();ownership.reset();armedMode=false;intervalMs=333; recognizer?.close(); recognizer = null; health.reset();recoveryStatus="" }
        }
        wasEnabled = current
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true;AirRemoteTv.onSent=null;feedback?.close();feedback=null; AirRemoteTv.stop(); recognition = "Camera stopped · disarmed"
        worker.execute { recognizer?.close(); recognizer = null; gestures.reset() }; worker.shutdown()
    }
    companion object {
        @Volatile private var recoveryStatus=""
        @Volatile var calibration=false
        @Volatile private var calibrationDetail=""
        @Volatile private var intendedCommand="None"
        @Volatile var recognition = "Camera not started"
        @Volatile private var inferenceState = "Not initialized"
        @Volatile private var handDetected = false
        @Volatile private var detectedGesture = "None"
        @Volatile private var lastInference = 0L
        fun diagnostics(): String {
            val fresh=SystemClock.elapsedRealtime()-lastInference<2000
            return "Camera: ${if(CameraService.active) "service active" else "stopped"} · Inference: $inferenceState" +
                "\nHand detected: ${if(!fresh) "unknown (no recent result)" else if(handDetected) "Yes" else "No"} · Gesture: ${if(fresh) detectedGesture else "None"}" +
                "\n$recognition${if(recoveryStatus.isEmpty()) "" else "\n$recoveryStatus"}${if(calibration) "\n$calibrationDetail" else ""}\nTV: ${if(AirRemoteTv.connected) "connected" else "disconnected"}\n${AirRemoteTv.lastCommand}" +
                if(AirRemoteTv.lastFailure.isEmpty()) "" else "\nLast connection error: ${AirRemoteTv.lastFailure}"
        }
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
