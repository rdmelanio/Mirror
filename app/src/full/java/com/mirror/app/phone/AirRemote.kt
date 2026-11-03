package com.mirror.app.phone

import android.content.Context
import androidx.camera.core.ImageProxy
import android.os.SystemClock
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors

/** One owned sampled YUV snapshot, converted off the analyzer; no ImageProxy retention or JPEG round trip. */
internal class AirRemote(private val context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val gate=AirFrameGate()
    private val raw=AirRawFrame()
    private val workload=AirWorkload()
    @Volatile var eco=true;private set
    private var nextThermal=0L
    @Volatile private var thermalReport=0
    @Volatile private var thermalReading="Unavailable"
    private var copyMs=0L
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
    @Volatile private var intervalMs=400L
    @Volatile private var feedback: AirFeedbackServer?=null
    private var feedbackAddress: String?=null
    private val health = AirRecognitionHealth({ SystemClock.elapsedRealtime() }) {
        gestures.reset();ownership.reset();armedMode=false;handDetected=false
        recognition="Recognition error · ${healthMessage()}"
    }
    private fun healthMessage():String=health.lastError
    private var wasEnabled = false
    init { AirRemoteTv.init(context) }
    fun needsFrame(): Boolean = !closed && enabled(context) && gate.available() && health.ready(SystemClock.elapsedRealtime()) && SystemClock.elapsedRealtime()-lastFrame>=intervalMs
    @Synchronized fun offer(cameraFrame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed || !enabled(context) || now - lastFrame < intervalMs || !health.ready(now) || !gate.acquire()) { skipped++;return }
        lastFrame = now
        try { val copyStart=SystemClock.elapsedRealtime();raw.copy(cameraFrame);copyMs=SystemClock.elapsedRealtime()-copyStart }
        catch(e:Exception) { worker.execute { try { health.run(now) { throw e };recoveryStatus=health.lastError } finally { gate.release() } };return }
        worker.execute {
            var inferenceStart=0L
            try {
                if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 250 || !health.ready(now)) return@execute
                health.run(now) frame@{
                workload.thermal(now,thermalReport)
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
                val prefsFrame=AirRemoteTv.prefs(context)
                val maxSide=if(workload.thermal>=3) 480 else prefsFrame.getInt("input_size",640).let { if(it==480) 480 else 640 }
                val image=raw.image(maxSide,prefsFrame.getBoolean("bitmap_input",false))
                    try {
                        val recognizeStart=SystemClock.elapsedRealtime()
                        val result = recognizer!!.recognizeForVideo(image, now)
                        if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 1200) {
                            session.update(SystemClock.elapsedRealtime(),emptyList(),AirRemoteTv.connected,calibration,intervalMs,.65f,50);armedMode=gestures.armed
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
                            val legacy=prefs.getBoolean("legacy",false)
                            val points=hand.map { AirPointing.Point(it.x(),it.y()) }
                            val direction=if(legacy) null else AirPointing.classify(points,prefs.getBoolean("mirror",true))
                            AirOwnership.Hand(x,y,size,direction ?: category?.categoryName().orEmpty(),if(direction!=null) AirPointing.quality(points) else category?.score() ?: 0f)
                        }
                        detectedGesture += " · direction " + hands.joinToString { it.gesture }
                        session.update(now,hands,AirRemoteTv.connected,calibrating,intervalMs,confidence,prefs.getInt("sensitivity",50),prefs.getBoolean("legacy",false),prefs.getLong("point_hold",400))
                        armedMode=gestures.armed && ownership.claimed
                        if(session.awaitingRelease) workload.reset()
                        workload.observe(completed,!session.awaitingRelease && (AirRemoteTv.connected || calibrating) && hands.any { it.gesture=="Open_Palm" && it.confidence>=confidence },armedMode)
                        eco=workload.stage==AirStage.ECO || workload.thermal>=3
                        performance="${workload.stage}${if(workload.thermal>=2) " · Thermal Limited" else ""} · thermal $thermalReading · copy ${copyMs}ms · skipped $skipped"
                        session.accepted?.let { intendedCommand=it.name }
                        calibrationDetail="${gestures.movement}\nIntended command: $intendedCommand · ${if(calibrating) "CALIBRATION: no transmission" else "live"}"
                        inferenceState="Model ready · %.1f fps · %d ms (YUV→RGB %d · inference %d)".format(java.util.Locale.US,observedFps,completed-inferenceStart,recognizeStart-decodeStart,completed-recognizeStart)
                        recognition=session.status + " · %d ms · %.1f fps".format(java.util.Locale.US,rate.latencyMs.toInt(),observedFps)

                    } finally { image.close() }

                }
                recoveryStatus=health.lastError
            } finally {
                if(inferenceStart>0) { rate.measured(SystemClock.elapsedRealtime()-inferenceStart);intervalMs=workload.interval(rate.latencyMs) }
                gate.release()
            }
        }
    }
    @Synchronized fun refresh() {
        if (closed) return
        val current = enabled(context)
        if (current) {
            val now=SystemClock.elapsedRealtime()
            if(now>=nextThermal) {
                nextThermal=now+10_000
                if(android.os.Build.VERSION.SDK_INT>=29) runCatching {
                    val level=context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
                    thermalReading=listOf("None","Light","Moderate","Severe","Critical","Emergency","Shutdown").getOrNull(level) ?: "Unknown";thermalReport=level
                }.onFailure { thermalReading="Unavailable" }
            }
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
            worker.execute { gestures.reset();ownership.reset();armedMode=false;intervalMs=400; recognizer?.close(); recognizer = null; raw.close();workload.reset();eco=true;health.reset();recoveryStatus="" }
        }
        wasEnabled = current
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true;AirRemoteTv.onSent=null;feedback?.close();feedback=null; AirRemoteTv.stop(); recognition = "Camera stopped · disarmed"
        worker.execute { recognizer?.close(); recognizer = null;raw.close(); gestures.reset() }; worker.shutdown()
    }
    companion object {
        @Volatile private var performance="Eco"
        @Volatile var skipped=0L
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
            return "$performance\nCamera ${"%.1f".format(java.util.Locale.US,CameraService.cameraFps)} fps: ${if(CameraService.active) "service active" else "stopped"} · Inference: $inferenceState" +
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
