package com.mirror.app.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener
import androidx.camera.core.ImageCapture
import com.mirror.app.core.mirrorPreferences
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import com.mirror.app.R
import com.mirror.app.core.LocalNetwork
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraService : LifecycleService() {
    private val analyzer = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var server: MjpegServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var destroyed = false
    private var starting = false
    private val main = Handler(Looper.getMainLooper())
    private var front = false
    private var fullHd = false
    private var boundAddress: String? = null
    private var opening = false
    @Volatile private var cameraEpoch = 0
    private var idleSince = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airRemote: AirRemote? = null
    private var nextAirCameraAttempt=0L
    private val standby get() = !AirRemote.enabled(this) && mirrorPreferences().getBoolean("standby", true) && !standbyFallback
    private val maintenance = object : Runnable {
        override fun run() {
            if (destroyed) return
            reconcileWifi()
            airRemote?.refresh()
            if (AirRemote.enabled(this@CameraService) && boundAddress != null && !cameraRunning && android.os.SystemClock.elapsedRealtime()>=nextAirCameraAttempt) {
                nextAirCameraAttempt=android.os.SystemClock.elapsedRealtime()+15_000;startCamera()
            }
            val now=android.os.SystemClock.elapsedRealtime()
            if(AirRemote.enabled(this@CameraService) && cameraRunning && lastCameraFrameAt>0 && now-lastCameraFrameAt>8000) {
                cameraFps=0.0
                note="Camera frames unavailable · service active · screen ${if(getSystemService(PowerManager::class.java).isInteractive) "on" else "locked/off"}"
                if(stalledSince==0L) stalledSince=now
                // Rebind an already-running foreground camera, with bounded retries under OS restrictions.
                if(now-stalledSince>=7000 && now>=nextAirCameraAttempt) {
                    nextAirCameraAttempt=now+30_000;stalledSince=now;stopCamera();startCamera()
                }
            } else stalledSince=0L
            val viewers = server?.viewerNames().orEmpty()
            demand()
            if (viewers.isEmpty()) {
                if (idleSince == 0L) idleSince = android.os.SystemClock.elapsedRealtime()
                if (standby && cameraRunning && android.os.SystemClock.elapsedRealtime() - idleSince >= 60_000) stopCamera()
            } else idleSince = 0L
            updateNotification(viewers)
            main.postDelayed(this, 1000)
        }
    }
    private var activeCamera:androidx.camera.core.Camera?=null
    private var fpsRanges=emptyList<android.util.Range<Int>>()
    private var appliedFps:android.util.Range<Int>?=null
    private var cameraFrames=0L
    private var cameraMeasuredAt=0L
    private var stalledSince=0L
    private fun demand() {
        val viewers=server?.viewerNames()?.isNotEmpty()==true
        wifi?.let { if(AirWorkload.wifiPerformance(viewers) && !it.isHeld) it.acquire() else if(!viewers && it.isHeld) it.release() }
        val needCpu=cameraRunning || opening || AirRemote.enabled(this)
        wake?.let { if(needCpu && !it.isHeld) it.acquire() else if(!needCpu && it.isHeld) it.release() }
        val camera=activeCamera ?: return
        val selected=AirWorkload.fpsRange(fpsRanges.map { it.lower to it.upper },AirRemote.enabled(this) && !viewers && airRemote?.eco==true)
        val range=fpsRanges.firstOrNull { it.lower==selected?.first && it.upper==selected?.second } ?: return
        if(range==appliedFps) return
        appliedFps=range
        val future=androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            androidx.camera.camera2.interop.CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,range).build())
        future.addListener({ runCatching { future.get() }.onFailure { note="Camera FPS override unavailable; downstream Eco throttling remains active" } },java.util.concurrent.Executor { task -> main.post(task) })
    }
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var orientationListener: OrientationEventListener? = null
    private var physicalRotation = Surface.ROTATION_0
    @Volatile private var streamOrientation = "landscape"
    private val preferencesChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "streamOrientation") streamOrientation = prefs.getString(key, "landscape") ?: "landscape"
        if (key == "standby") main.post { if (!standby && boundAddress != null) startCamera() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (starting) {
            if (intent?.action == RETRY && standbyFallback && boundAddress != null) startCamera()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error = "Camera permission is required"; stopSelf(); return START_NOT_STICKY
        }
        airRemote = AirRemote(applicationContext)
        starting = true; active = true; error = null; standbyFallback = mirrorPreferences().getBoolean("standbyFallback", false); stats = StreamStats()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("camera", "Camera streaming", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
            val notification = notification("Standby - waiting for TV")
            if (Build.VERSION.SDK_INT >= 29) startForeground(8080, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(8080, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mirror:camera").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mirror:stream")
            front = intent?.getBooleanExtra("front", false) ?: false
            fullHd = intent?.getBooleanExtra("fullHd", false) ?: false
            physicalRotation = intent?.getIntExtra("rotation", Surface.ROTATION_0) ?: Surface.ROTATION_0
            physicalPortrait = physicalRotation == Surface.ROTATION_0 || physicalRotation == Surface.ROTATION_180
            streamOrientation = mirrorPreferences().getString("streamOrientation", "landscape") ?: "landscape"
            mirrorPreferences().registerOnSharedPreferenceChangeListener(preferencesChanged)
            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees == ORIENTATION_UNKNOWN) return
                    // Ignore positions near quadrant boundaries to avoid flicker when tilted.
                    val quadrant = ((degrees + 45) / 90) % 4
                    val distance = kotlin.math.abs(((degrees - quadrant * 90 + 540) % 360) - 180)
                    if (distance > 35) return
                    val rotation = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_270, Surface.ROTATION_180, Surface.ROTATION_90)[quadrant]
                    physicalPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
                    if (rotation != physicalRotation) {
                        physicalRotation = rotation; analysis?.targetRotation = rotation; capture?.targetRotation = rotation
                    }
                }
            }.also { if (it.canDetectOrientation()) it.enable() }
            val managerNetwork = getSystemService(ConnectivityManager::class.java)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { main.post { reconcileWifi() } }
                override fun onLost(network: android.net.Network) { main.post { reconcileWifi() } }
                override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) { main.post { reconcileWifi() } }
            }.also { managerNetwork.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it) }
            reconcileWifi(); main.post(maintenance)
        } catch (failure: Exception) { fail(failure) }
        return START_NOT_STICKY
    }
    private fun startCamera() {
        if (destroyed || boundAddress == null || cameraRunning || opening) return
        opening = true
        val epoch = cameraEpoch
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (destroyed || boundAddress == null || cameraEpoch != epoch) return@addListener
                try {
                    provider = future.get()
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    require(provider!!.hasCamera(selector)) { "This device does not have the selected camera" }
                    val selectedSize = if (fullHd || AirRemote.enabled(this@CameraService)) Size(1920, 1080) else Size(1280, 720)
                    val cameraInfo = selector.filter(provider!!.availableCameraInfos).first()
                    val ranges = Camera2CameraInfo.from(cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                    fpsRanges=ranges.toList()
                    val chosen=AirWorkload.fpsRange(ranges.map { it.lower to it.upper },false)
                    val fps=ranges.firstOrNull { it.lower==chosen?.first && it.upper==chosen?.second }
                    @Suppress("RestrictedApi")
                    val builder = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(selectedSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                            .setResolutionFilter { sizes, _ ->
                                sizes.filter { it.width <= selectedSize.width && it.height <= selectedSize.height }
                            }.build())
                        .setTargetRotation(physicalRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setOutputImageRotationEnabled(false).setOnePixelShiftEnabled(false)
                    fps?.let { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    val analysis = builder.build()
                    this.analysis = analysis
                    val capture = ImageCapture.Builder().setTargetRotation(physicalRotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    this.capture = capture
                    val encoder = JpegEncoder()
                    var quality = 70
                    var encodedFrames = 0
                    analysis.setAnalyzer(analyzer) { image ->
                        try {
                            if (!destroyed && cameraEpoch == epoch) {
                                val measured=android.os.SystemClock.elapsedRealtime()
                                if(cameraMeasuredAt==0L) cameraMeasuredAt=measured
                                cameraFrames++
                                lastCameraFrameAt=measured
                                cameraResolution="${image.width}x${image.height} rotation=${image.imageInfo.rotationDegrees}"
                                if(measured-cameraMeasuredAt>=1000) { cameraFps=cameraFrames*1000.0/(measured-cameraMeasuredAt);cameraFrames=0;cameraMeasuredAt=measured }
                                if(AirRemote.enabled(this@CameraService)) airRemote?.offer(image)
                                val viewers=server?.viewerNames()?.isNotEmpty()==true
                                if(AirWorkload.encodeJpeg(AirRemote.enabled(this@CameraService),viewers)) {
                                    val start=System.nanoTime()
                                    val jpeg=encoder.encode(image,quality,streamOrientation)
                                    stats.rotationDegrees=0;stats.encoded((System.nanoTime()-start)/1_000_000.0)
                                    if(!destroyed && cameraEpoch==epoch) server?.publish(jpeg,0)
                                    if(++encodedFrames%30==0 && stats.encodeMs>25.0 && quality>60) quality-=2
                                }
                            }
                        } catch (failure: Exception) { error = "Camera frame failed: ${failure.message}" }
                        finally { image.close() }
                    }
                    val camera = provider!!.bindToLifecycle(this, selector, analysis, capture)
                    activeCamera=camera;appliedFps=fps
                    server?.controls = CameraControls(this, camera, capture)
                    cameraRunning = true; opening = false;demand()
                    camera.cameraInfo.cameraState.observe(this) { state ->
                        if (cameraEpoch == epoch && state.error != null && (server?.viewerNames()?.isNotEmpty() == true || AirRemote.enabled(this@CameraService))) {
                            standbyFailed(); stopCamera()
                        }
                    }
                } catch (failure: Exception) { opening = false; standbyFailed(); error = "Camera could not wake: ${failure.message}. Open Mirror on the phone." }
            }, java.util.concurrent.Executor { task -> android.os.Handler(mainLooper).post(task) })
        } catch (failure: Exception) { opening = false; standbyFailed(); error = failure.message }
    }
    private fun standbyFailed() {
        standbyFallback = true
        mirrorPreferences().edit().putBoolean("standbyFallback", true).apply()
        note = "Android refused to wake the camera in the background. Keep Mirror open briefly; the camera will stay running for this device."
    }
    private fun stopCamera() {
        cameraEpoch++;activeCamera=null;appliedFps=null; opening = false; cameraRunning = false;cameraFps=0.0
        analysis?.clearAnalyzer(); provider?.unbindAll(); analysis = null; capture = null
        server?.controls = null; server?.clearFrames()
    }
    private fun reconcileWifi() {
        if (destroyed) return
        val address = LocalNetwork.address(this)
        if (address == boundAddress) { if (address == null) error = "Not on Wi-Fi - connect to the same Wi-Fi as your TV"; return }
        stopCamera(); server?.close(); server = null
        registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }; registration = null
        boundAddress = address
        if (address == null) { error = "Not on Wi-Fi - connect to the same Wi-Fi as your TV"; return }
        try {
            error = null
            server = MjpegServer(Build.MODEL, stats, PhoneSecurity.get(this), java.net.InetAddress.getByName(address),
                { ip -> main.post { blockedNotification(ip) } }, { names -> main.post {
                    if (names.isNotEmpty() && server?.viewerNames()?.isNotEmpty() == true) { idleSince = 0; startCamera() }
                    demand();updateNotification(names)
                } }).also { it.start() }
            advertise()
            if (!standby) startCamera()
        } catch (failure: Exception) { error = "Wi-Fi server failed: ${failure.message}"; boundAddress = null }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PhoneActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "camera").setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror camera")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, "camera").setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror").setContentText("Camera service active").build())
            .setContentText(text).setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private var notificationText = ""
    private fun updateNotification(names: List<String>) {
        ClockMirrorState.update(active, boundAddress != null && standby, names.isNotEmpty())
        val text = if (boundAddress == null) "Not on Wi-Fi - connect to the same Wi-Fi as your TV"
            else if (names.isEmpty()) if (standby) "Standby - waiting for TV" else "Camera ready - 0 viewer(s)"
            else "Streaming to ${names.distinct().joinToString(", ")} - ${names.size} viewer(s)"
        if (text != notificationText) {
            notificationText = text
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                getSystemService(NotificationManager::class.java).notify(8080, notification(text))
        }
    }
    private fun blockedNotification(ip: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("blocked", "Blocked connections", NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        manager.notify(ip.hashCode(), Notification.Builder(this, "blocked").setSmallIcon(R.drawable.ic_mirror)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, "blocked").setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror").build())
            .setContentTitle("Blocked connection from $ip").setAutoCancel(true).build())
    }
    private fun fail(failure: Exception) { error = failure.message ?: "Could not start camera"; stopSelf() }
    private fun advertise() {
        val nsd = getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply { serviceName = Build.MODEL; serviceType = "_mirrorcam._tcp."; port = 8080; setAttribute("cameraId", PhoneSecurity.get(this@CameraService).cameraId) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { registration = null }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { registration = null } // The address still works if a router blocks discovery.
    }
    override fun onDestroy() {
        destroyed = true; airRemote?.close(); airRemote = null; active = false; cameraRunning = false
        ClockMirrorState.update(false, false, false)
        main.removeCallbacksAndMessages(null)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }; networkCallback = null
        orientationListener?.disable(); orientationListener = null
        mirrorPreferences().unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        analysis = null; capture = null
        provider?.unbindAll(); server?.close(); server = null
        registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        analyzer.shutdownNow()
        wake?.let { if (it.isHeld) it.release() }; wifi?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        @Volatile var cameraResolution="Unavailable"
        @Volatile var lastCameraFrameAt=0L
        @Volatile var cameraFps=0.0
        @Volatile var stats = StreamStats()
        @Volatile var cameraRunning = false
        @Volatile var standbyFallback = false
        @Volatile var note: String? = null
        const val RETRY = "com.mirror.app.RETRY"
        const val STOP = "com.mirror.app.STOP"
        @Volatile var active = false
        @Volatile var physicalPortrait = true
        @Volatile var error: String? = null
    }
}



