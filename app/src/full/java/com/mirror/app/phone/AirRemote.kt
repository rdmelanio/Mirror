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
    private val handLock=AirHandLock()
    private val measured=AirPerformance()
    private var recognizerConfig=""
    private var gpuFailed=false
    private var delegate="CPU"
    private var delegateFallback=""
    private var summaryAt=0L
    private var launchMessage=""
    private val completedTimes=java.util.ArrayDeque<Long>()
    private fun createRecognizer(gpu:Boolean,hands:Int):GestureRecognizer = GestureRecognizer.createFromOptions(context,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task")
                .setDelegate(if(gpu) com.google.mediapipe.tasks.core.Delegate.GPU else com.google.mediapipe.tasks.core.Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(hands)
            .setMinHandDetectionConfidence(.65f).setMinHandPresenceConfidence(.65f).setMinTrackingConfidence(.65f).build())
    private fun model(gpu:Boolean,hands:Int) {
        val useGpu=gpu && !gpuFailed
        val config="$useGpu/$hands"
        if(recognizer!=null && config==recognizerConfig) return
        recognizer?.close();recognizer=null
        try { recognizer=createRecognizer(useGpu,hands);delegate=if(useGpu) "GPU" else "CPU" }
        catch(e:Exception) {
            if(!useGpu) throw AirModelUnavailable(e)
            gpuFailed=true;delegateFallback="GPU initialization failed; CPU fallback"
            recognizer=createRecognizer(false,hands);delegate="CPU"
        } catch(e:LinkageError) {
            if(!useGpu) throw e
            gpuFailed=true;delegateFallback="GPU unavailable; CPU fallback"
            recognizer=createRecognizer(false,hands);delegate="CPU"
        }
        recognizerConfig="${useGpu && !gpuFailed}/$hands"
    }
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
        {},
        { if(!calibration) AirRemoteTv.send(it) },household=true,launch={ app -> if(!calibration) {
            history.launch(app,"accepted");history.launch(app,if(feedback?.launch(app)==true) "queued" else "discarded")
        } })
    private val gestures get() = session.gestures
    private val ownership get() = session.ownership
    private val rate = AirInferenceRate()
    @Volatile private var armedMode=false
    @Volatile private var intervalMs=400L
    @Volatile private var feedback: AirFeedbackServer?=null
    private var feedbackAddress: String?=null
    private val health = AirRecognitionHealth({ SystemClock.elapsedRealtime() }) {
        session.pauseRecognition(SystemClock.elapsedRealtime());armedMode=session.sessionActive;handDetected=false
        recognition="Recognition error · ${healthMessage()}"
        history.record("Recognition error",session.trackingState,"None",0f,null,"Paused",observedFps,rate.latencyMs,thermalReading,"inference recovery")
    }
    private fun healthMessage():String=health.lastError
    private var wasEnabled = false
    private var feedbackEvent=0L
    private var progressShown=false
    private var maintenanceQueued=java.util.concurrent.atomic.AtomicBoolean()
    private fun publishState(now:Long) {
        if(calibration) { feedbackEvent=session.eventSerial;return }
        if(session.eventSerial>feedbackEvent) {
            feedbackEvent=session.eventSerial
            if(session.event in setOf("ACTIVE","TRANSFERRED","EXPIRED")) feedback?.post(session.event)
        }
        val progress=session.claimProgress
        if(progress>=500 && !progressShown) { feedback?.post("CLAIMING");progressShown=true }
        if(progress==0L) progressShown=false
        val state=when {
            !session.sessionActive -> if(session.awaitingRelease) "EXPIRED" else "STANDBY"
            session.trackingState=="CONNECTION" -> "CONNECTION_UNAVAILABLE"
            session.trackingState!="OWNED" -> "PAUSED"
            session.processing(now) -> "ACTIVE"
            else -> "IDLE"
        }
        feedback?.snapshot(org.json.JSONObject().put("state",state).put("active",session.sessionActive).put("tracking",session.trackingState)
            .put("activity",session.activitySerial).put("progress",(progress/250)*250))
    }
    init {
        AirRemoteTv.init(context)
        val p=AirRemoteTv.prefs(context)
        if(!p.contains("session_timeout")) p.edit().putLong("session_timeout",60_000).apply()
    }
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
                if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 250 || !health.ready(now)) { staleFrames++;return@execute }
                health.run(now) frame@{
                workload.thermal(now,thermalReport)
                inferenceStart=SystemClock.elapsedRealtime()
                inferenceState="Initializing model"
                AirRemoteTv.connect(context)
                val prefsFrame=AirRemoteTv.prefs(context)
                val benchmark=AirBenchmark.profile(now)
                val handsCount=benchmark?.hands ?: prefsFrame.getInt("model_hands",4).let { if(it==2) 2 else 4 }
                model(benchmark?.gpu ?: prefsFrame.getBoolean("gpu",false),handsCount)
                inferenceState="Model ready · processing"
                val decodeStart=SystemClock.elapsedRealtime()
                val maxSide=if(workload.thermal>=3) 480 else (benchmark?.size ?: prefsFrame.getInt("input_size",640)).let { if(it==480) 480 else 640 }
                val lockMode=if(benchmark?.crop==false) AirHandLock.Mode.WIDE else when(prefsFrame.getString("hand_lock","auto")) { "wide" -> AirHandLock.Mode.WIDE;"distance" -> AirHandLock.Mode.DISTANCE;else -> AirHandLock.Mode.AUTO }
                val roi=handLock.input(now,lockMode,session.trackingOwned)
                val image=raw.image(maxSide,prefsFrame.getBoolean("bitmap_input",false),roi)
                    try {
                        val recognizeStart=SystemClock.elapsedRealtime()
                        val result = try { recognizer!!.recognizeForVideo(image, now) } catch(e:RuntimeException) {
                            if(delegate=="GPU") {
                                recognizer?.close();recognizer=null;gpuFailed=true;delegate="CPU";delegateFallback="GPU inference failed; CPU fallback on next fresh frame"
                            }
                            throw e
                        } catch(e:LinkageError) {
                            if(delegate!="GPU") throw e
                            runCatching { recognizer?.close() };recognizer=null;gpuFailed=true;delegate="CPU";delegateFallback="GPU runtime unavailable; CPU fallback on next fresh frame"
                            throw IllegalStateException(delegateFallback,e)
                        }
                        if (closed || !enabled(context) || SystemClock.elapsedRealtime() - now > 1200) {
                            staleFrames++;session.pauseRecognition(SystemClock.elapsedRealtime());armedMode=session.sessionActive
                            recognition=if(closed || !enabled(context)) "Disabled · disarmed" else "Standby · inference too slow; sampling reduced"
                            return@frame
                        }
                        val completed=SystemClock.elapsedRealtime()
                        completedTimes.add(completed);while(completedTimes.size>12 || completedTimes.size>1 && completed-completedTimes.first>4000) completedTimes.removeFirst()
                        observedFps=if(completedTimes.size>1) (completedTimes.size-1)*1000.0/(completed-completedTimes.first).coerceAtLeast(1) else 0.0
                        lastResult=completed
                        lastInference=completed
                        val names=result.gestures().mapNotNull { it.maxByOrNull { c -> c.score() } }
                        handDetected=result.landmarks().isNotEmpty()
                        detectedGesture=names.joinToString { "${it.categoryName()} ${ (it.score()*100).toInt()}%" }.ifEmpty { "None" }
                        val prefs=AirRemoteTv.prefs(context)
                        val calibrating=calibration
                        val confidence=AirConfidence.fraction(prefs.getInt("confidence",65))
                        val fullPoints=result.landmarks().map { hand -> hand.map { roi.full(AirPointing.Point(it.x(),it.y())) } }
                        val hands=fullPoints.mapIndexed { index, hand ->
                            val category=result.gestures().getOrNull(index)?.maxByOrNull { it.score() }
                            val palm=listOf(0,5,9,13,17).map { hand[it] }
                            val rawX=palm.map { it.x }.average().toFloat()
                            val x=if(prefs.getBoolean("mirror",true)) 1f-rawX else rawX
                            val y=palm.map { it.y }.average().toFloat()
                            val size=kotlin.math.hypot(hand[0].x-hand[9].x,hand[0].y-hand[9].y)
                            val legacy=prefs.getBoolean("legacy",false)
                            val points=hand
                            val direction=if(legacy) category?.categoryName().orEmpty() else AirShortcutPose.classify(points) ?: AirPointing.resolve(points,prefs.getBoolean("mirror",true),category?.categoryName().orEmpty(),category?.score() ?: 0f,confidence)
                            AirOwnership.Hand(x,y,size,direction,category?.score() ?: 0f)
                        }
                        detectedGesture += " · direction " + hands.joinToString { it.gesture }
                        session.update(now,hands,AirRemoteTv.connected,calibrating,intervalMs,confidence,prefs.getInt("sensitivity",50),prefs.getBoolean("legacy",false),prefs.getLong("point_hold",400),prefs.getLong("session_timeout",60_000),
                            if(prefs.getString("navigation","joystick")=="pointing") AirNavigation.POINTING else AirNavigation.JOYSTICK,
                            prefs.getInt("joystick_sensitivity",50),AirApp.entries.filter { prefs.getBoolean("app_${it.name}",true) }.toSet(),feedback?.launchSupported()==true)
                        val decisionEnd=SystemClock.elapsedRealtime()
                        val sample=AirPerformance.Sample((inferenceStart-now).coerceAtLeast(0),recognizeStart-decodeStart,completed-recognizeStart,decisionEnd-completed)
                        measured.add(sample);benchmark?.let { AirBenchmark.measured(decisionEnd,it,delegate,sample) }
                        val owned=session.trackedHand
                        val ownerIndex=if(owned==null) -1 else hands.indices.minByOrNull { i -> kotlin.math.hypot(hands[i].x-owned.x,hands[i].y-owned.y) } ?: -1
                        handLock.observe(fullPoints.getOrNull(ownerIndex),lockMode)
                        armedMode=session.sessionActive
                        if(session.awaitingRelease) workload.reset()
                        workload.observe(completed,!session.awaitingRelease && session.claimProgress>0,session.processing(completed))
                        publishState(completed)
                        history.record(if(calibration) "Calibration" else if(session.sessionActive) "Active" else "Standby",session.trackingState,session.diagnosticPose,session.diagnosticScore,session.accepted,
                            if(AirRemoteTv.connected) "Connected" else "Disconnected",observedFps,rate.latencyMs,thermalReading,AirDiagnostics.reason(session.status))
                        eco=workload.stage==AirStage.ECO || workload.thermal>=3
                        performance="${prefs.getString("navigation","joystick")} · ${handLock.state} · $delegate $handsCount hands $maxSide px${if(delegateFallback.isNotEmpty()) " · $delegateFallback" else ""} · ${workload.stage}${if(workload.thermal>=2) " · Thermal Limited" else ""} · thermal $thermalReading · copy ${copyMs}ms · skipped $skipped · stale $staleFrames"
                        if(decisionEnd-summaryAt>=2000) {
                            summaryAt=decisionEnd
                            performanceMetrics=measured.summary()+"\n"+AirRemoteTv.timings()
                            history.performance(workload.stage.name,delegate,roi!=AirRoi.FULL,prefs.getString("navigation","joystick")=="joystick",sample.frameAge,sample.conversion,sample.inference,sample.decision)
                        }
                        session.accepted?.let { intendedCommand=it.name }
                        calibrationDetail="${session.status}\nIntended command: $intendedCommand · ${if(calibrating) "CALIBRATION: no transmission" else "live"}"
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
            if(maintenanceQueued.compareAndSet(false,true)) worker.execute {
                try {
                    session.heartbeat(SystemClock.elapsedRealtime());armedMode=session.sessionActive
                    history.record(if(calibration) "Calibration" else if(session.sessionActive) "Active" else "Standby",session.trackingState,session.diagnosticPose,session.diagnosticScore,null,
                        if(AirRemoteTv.connected) "Connected" else "Disconnected",observedFps,rate.latencyMs,thermalReading,AirDiagnostics.reason(session.status))
                    if(!session.processing(SystemClock.elapsedRealtime())) workload.observe(SystemClock.elapsedRealtime(),session.claimProgress>0,false)
                    intervalMs=workload.interval(rate.latencyMs);eco=workload.stage==AirStage.ECO || workload.thermal>=3
                    publishState(SystemClock.elapsedRealtime())
                } finally { maintenanceQueued.set(false) }
            }
            feedbackStatus=feedback?.launchStatus() ?: "TV feedback listener not connected"
            if(feedbackStatus!=launchMessage) {
                launchMessage=feedbackStatus
                val app=AirApp.entries.firstOrNull { feedbackStatus.startsWith(it.name) }
                val result=when { feedbackStatus.contains("not installed") -> "missing";feedbackStatus.contains("blocked") -> "blocked";feedbackStatus.contains("not confirmed") -> "attempted";feedbackStatus.contains("expired") -> "expired";feedbackStatus.contains("failed") -> "failed";else -> null }
                if(app!=null && result!=null) history.launch(app,result)
            }
            AirRemoteTv.connect(context)
            AirRemoteTv.onSent={ command -> if(!calibration) {
                feedback?.post("SENT_${command.name}")
                history.record("Transmitted","Transport",command.name,1f,command,"Transmitted",observedFps,rate.latencyMs,thermalReading,AirDiagnostics.reason(session.status))
            } }
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
            worker.execute { session.reset();history.record("Disarmed","NONE","None",0f,null,"Disabled",observedFps,rate.latencyMs,thermalReading,"explicit disable");armedMode=false;intervalMs=400; recognizer?.close(); recognizer = null; raw.close();handLock.reset();completedTimes.clear();workload.reset();eco=true;health.reset();gpuFailed=false;delegateFallback="";recoveryStatus="" }
        }
        wasEnabled = current
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true;AirBenchmark.stop();AirRemoteTv.onSent=null;feedback?.close();feedback=null; AirRemoteTv.stop(); recognition = "Camera stopped · disarmed"
        worker.execute { recognizer?.close(); recognizer = null;raw.close(); session.reset();history.record("Disarmed","NONE","None",0f,null,"Closed",observedFps,rate.latencyMs,thermalReading,"service shutdown") }; worker.shutdown()
    }
    companion object {
        val history=AirDiagnostics { SystemClock.elapsedRealtime() }
        @Volatile private var performance="Eco"
        @Volatile private var feedbackStatus="TV companion launch capability not yet negotiated"
        @Volatile private var performanceMetrics="No completed measurements"
        @Volatile var skipped=0L
        @Volatile private var staleFrames=0L
        @Volatile private var recoveryStatus=""
        @Volatile private var calibrationRequested=false
        var calibration:Boolean
            get()=calibrationRequested || AirBenchmark.running
            set(value) { calibrationRequested=value;if(value) AirRemoteTv.discardPending() }
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
                "\n$performanceMetrics\n$recognition${if(recoveryStatus.isEmpty()) "" else "\n$recoveryStatus"}${if(calibration) "\n$calibrationDetail" else ""}\nTV: ${if(AirRemoteTv.connected) "connected" else "disconnected"}\n${AirRemoteTv.lastCommand}\n${feedbackStatus}" +
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
