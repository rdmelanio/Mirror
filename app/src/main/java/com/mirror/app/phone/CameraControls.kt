package com.mirror.app.phone

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface ControlEndpoint {
    fun status(): JSONObject
    fun control(parameters: Map<String, String>): JSONObject
}

/** HTTP workers wait for CameraX callbacks; the main/camera analysis threads never block. */
class CameraControls(private val context: Context, private val camera: Camera,
                     private val capture: ImageCapture) : ControlEndpoint {
    private val main = ContextCompat.getMainExecutor(context)
    override fun status(): JSONObject {
        val zoom = camera.cameraInfo.zoomState.value
        return JSONObject().put("controls", true).put("zoom", zoom?.zoomRatio ?: 1f)
            .put("minZoom", zoom?.minZoomRatio ?: 1f).put("maxZoom", zoom?.maxZoomRatio ?: 1f)
            .put("hasFlash", camera.cameraInfo.hasFlashUnit())
            .put("torch", camera.cameraInfo.torchState.value == TorchState.ON)
    }
    override fun control(parameters: Map<String, String>): JSONObject {
        if (parameters.isEmpty() || parameters.keys.any { it !in listOf("zoom", "torch", "focus", "action") })
            return failure("Use zoom, torch=on|off, focus=center, or action=snapshot")
        val ratio = parameters["zoom"]?.toFloatOrNull()
        if (parameters.containsKey("zoom") && (ratio == null || !ratio.isFinite())) return failure("Invalid zoom ratio")
        if (parameters["torch"]?.let { it != "on" && it != "off" } == true) return failure("Use torch=on|off")
        if (parameters["focus"]?.let { it != "center" } == true) return failure("Use focus=center")
        if (parameters["action"]?.let { it != "snapshot" } == true) return failure("Use action=snapshot")
        return try {
            ratio?.let { requested -> await {
                val state = camera.cameraInfo.zoomState.value ?: error("Camera is not ready")
                camera.cameraControl.setZoomRatio(requested.coerceIn(state.minZoomRatio, state.maxZoomRatio))
            } }
            parameters["torch"]?.let { torch ->
                check(camera.cameraInfo.hasFlashUnit()) { "This camera has no flash unit" }
                await { camera.cameraControl.enableTorch(torch == "on") }
            }
            parameters["focus"]?.let {
                await {
                    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
                    camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(5, TimeUnit.SECONDS).build())
                }
            }
            val saved = if (parameters["action"] == "snapshot") snapshot() else null
            status().put("ok", true).apply { saved?.let { put("saved", it); put("message", "Saved to phone gallery") } }
        } catch (error: Exception) { failure(error.cause?.message ?: error.message ?: "Camera control failed") }
    }
    private fun <T> await(action: () -> com.google.common.util.concurrent.ListenableFuture<T>) {
        val done = CompletableFuture<T>()
        main.execute {
            try {
                val future = action()
                future.addListener({
                    try { done.complete(future.get()) } catch (e: Exception) { done.completeExceptionally(e) }
                }, main)
            } catch (e: Exception) { done.completeExceptionally(e) }
        }
        done.get(10, TimeUnit.SECONDS)
    }
    private fun snapshot(): String {
        if (Build.VERSION.SDK_INT <= 28) check(ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Allow gallery storage access on the phone and restart streaming"
        }
        val name = "Mirror_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mirror")
            else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Mirror")
                check(folder.isDirectory || folder.mkdirs()) { "Cannot create Pictures/Mirror" }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(folder, name).absolutePath)
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = false }).build()
        val done = CompletableFuture<String>()
        main.execute {
            try {
                capture.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        done.complete(result.savedUri?.toString() ?: name)
                    }
                    override fun onError(exception: ImageCaptureException) { done.completeExceptionally(exception) }
                })
            } catch (e: Exception) { done.completeExceptionally(e) }
        }
        return done.get(12, TimeUnit.SECONDS)
    }
    private fun failure(message: String) = status().put("ok", false).put("error", message)
}
