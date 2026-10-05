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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (starting) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error = "Camera permission is required"; stopSelf(); return START_NOT_STICKY
        }
        starting = true; active = true; error = null; stats = StreamStats()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("camera", "Camera streaming", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, PhoneActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "camera").setSmallIcon(R.drawable.ic_mirror)
                .setContentTitle("Mirror camera is streaming").setContentText("Open Mirror to manage the stream")
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(8080, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(8080, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mirror:camera").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mirror:stream").apply { acquire() }
            server = MjpegServer(Build.MODEL, stats).also { it.start() }
            val front = intent?.getBooleanExtra("front", false) ?: false
            val fullHd = intent?.getBooleanExtra("fullHd", false) ?: false
            val rotation = intent?.getIntExtra("rotation", Surface.ROTATION_0) ?: Surface.ROTATION_0
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (destroyed) return@addListener
                try {
                    provider = future.get()
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    require(provider!!.hasCamera(selector)) { "This device does not have the selected camera" }
                    val selectedSize = if (fullHd) Size(1920, 1080) else Size(1280, 720)
                    val cameraInfo = selector.filter(provider!!.availableCameraInfos).first()
                    val ranges = Camera2CameraInfo.from(cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                    val fps = ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
                        ?: ranges.filter { it.upper >= 24 && it.upper <= 30 }.maxByOrNull { it.lower }
                        ?: ranges.filter { it.upper >= 30 }.minByOrNull { it.upper }
                    @Suppress("RestrictedApi")
                    val builder = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(selectedSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                            .setResolutionFilter { sizes, _ ->
                                sizes.filter { it.width <= selectedSize.width && it.height <= selectedSize.height }
                            }.build())
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setOutputImageRotationEnabled(false).setOnePixelShiftEnabled(false)
                    fps?.let { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    val analysis = builder.build()
                    val encoder = JpegEncoder()
                    var quality = 70
                    var encodedFrames = 0
                    analysis.setAnalyzer(analyzer) { image ->
                        try {
                            if (!destroyed) {
                                val start = System.nanoTime()
                                val jpeg = encoder.encode(image, quality)
                                val degrees = image.imageInfo.rotationDegrees
                                stats.rotationDegrees = degrees
                                stats.encoded((System.nanoTime() - start) / 1_000_000.0)
                                server?.publish(jpeg, degrees)
                                if (++encodedFrames % 30 == 0 && stats.encodeMs > 25.0 && quality > 60) quality -= 2
                            }
                        } catch (failure: Exception) { error = "Camera frame failed: ${failure.message}" }
                        finally { image.close() }
                    }
                    provider!!.bindToLifecycle(this, selector, analysis)
                    advertise()
                } catch (failure: Exception) { fail(failure) }
            }, java.util.concurrent.Executor { task -> android.os.Handler(mainLooper).post(task) })
        } catch (failure: Exception) { fail(failure) }
        return START_NOT_STICKY
    }
    private fun fail(failure: Exception) { error = failure.message ?: "Could not start camera"; stopSelf() }
    private fun advertise() {
        val nsd = getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply { serviceName = Build.MODEL; serviceType = "_mirrorcam._tcp."; port = 8080 }
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
        destroyed = true; active = false
        provider?.unbindAll(); server?.close(); server = null
        registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        analyzer.shutdownNow()
        wake?.let { if (it.isHeld) it.release() }; wifi?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        @Volatile var stats = StreamStats()
        const val STOP = "com.mirror.app.STOP"
        @Volatile var active = false
        @Volatile var error: String? = null
    }
}
