package com.mirror.app.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mirror.app.core.LauncherActivity
import com.mirror.app.core.LocalNetwork
import com.mirror.app.core.action
import com.mirror.app.core.insetContent
import com.mirror.app.core.label
import com.mirror.app.core.mirrorPreferences

class PhoneActivity : Activity() {
    private lateinit var start: Button
    private lateinit var status: TextView
    private lateinit var cameras: RadioGroup
    private lateinit var resolutions: RadioGroup
    private var front = false
    private var fullHd = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        front = mirrorPreferences().getBoolean("phoneFront", false)
        fullHd = mirrorPreferences().getBoolean("phoneFullHd", false)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("Mirror · Phone camera", 28f))
        start = action("Start") { if (CameraService.active) stopService(Intent(this, CameraService::class.java)) else requestStart() }
        content.addView(start)
        content.addView(label("Camera"))
        cameras = choices(listOf("Rear", "Front"), if (front) 1 else 0) { front = it == 1; save() }
        content.addView(cameras)
        content.addView(label("Resolution"))
        resolutions = choices(listOf("720p", "1080p"), if (fullHd) 1 else 0) { fullHd = it == 1; save() }
        content.addView(resolutions)
        status = label("").apply { setTextIsSelectable(true) }; content.addView(status)
        content.addView(action("Battery optimization settings") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        content.addView(action("Change role") {
            stopService(Intent(this, CameraService::class.java)); LauncherActivity.changeRole(this)
        })
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll); insetContent(content); start.requestFocus(); update()
    }
    private fun choices(names: List<String>, selected: Int, changed: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        names.forEachIndexed { index, name -> group.addView(RadioButton(this).apply {
            id = android.view.View.generateViewId(); text = name; tag = index; isChecked = index == selected
        }) }
        group.setOnCheckedChangeListener { _, id -> group.findViewById<RadioButton>(id)?.let { changed(it.tag as Int) } }
        return group
    }
    private fun save() { mirrorPreferences().edit().putBoolean("phoneFront", front).putBoolean("phoneFullHd", fullHd).apply() }
    private fun requestStart() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10) else begin()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) begin()
            else Toast.makeText(this, "Allow camera access to start streaming", Toast.LENGTH_LONG).show()
        }
    }
    private fun begin() {
        try {
            @Suppress("DEPRECATION") val rotation = windowManager.defaultDisplay.rotation
            startForegroundService(Intent(this, CameraService::class.java)
                .putExtra("front", front).putExtra("fullHd", fullHd).putExtra("rotation", rotation))
        } catch (failure: Exception) { Toast.makeText(this, "Could not start: ${failure.message}", Toast.LENGTH_LONG).show() }
    }
    private fun update() {
        start.text = if (CameraService.active) "Stop" else "Start"
        for (group in listOf(cameras, resolutions)) for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !CameraService.active
        status.text = "${if (CameraService.active) "Streaming at" else "Stream address:"} http://${LocalNetwork.address(this)}:8080/video" +
            (CameraService.error?.let { "\n$it" } ?: "")
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
