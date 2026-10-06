package com.mirror.app.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.EditText
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
    private var clockPanel: ClockSettingsPanel? = null
    private lateinit var start: Button
    private lateinit var status: TextView
    private lateinit var cameras: RadioGroup
    private lateinit var resolutions: RadioGroup
    private lateinit var orientations: RadioGroup
    private var pairingDialog: AlertDialog? = null
    private val security get() = PhoneSecurity.get(this)
    private var orientation = "landscape"
    private var front = false
    private var fullHd = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientation = mirrorPreferences().getString("streamOrientation", "landscape") ?: "landscape"
        front = mirrorPreferences().getBoolean("phoneFront", false)
        fullHd = mirrorPreferences().getBoolean("phoneFullHd", false)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("Mirror · Phone camera", 28f))
        start = action("Start") { if (CameraService.active) stopService(Intent(this, CameraService::class.java)) else requestStart() }
        content.addView(start)
        content.addView(CheckBox(this).apply {
            text = "Standby - camera turns on only when your TV connects"; isChecked = mirrorPreferences().getBoolean("standby", true)
            setOnCheckedChangeListener { _, enabled -> mirrorPreferences().edit().putBoolean("standby", enabled).apply() }
        })
        content.addView(action("Pair new TV") { showPairing() })
        content.addView(action("Paired devices") { showDevices() })
        content.addView(action("Allow browser viewing: ${if (security.browserEnabled()) "On" else "Off"}") {
            val button = content.getChildAt(5) as? Button
            if (security.browserEnabled()) {
                security.setBrowserPassword(null); button?.text = "Allow browser viewing: Off"
            } else {
                val password = EditText(this).apply { hint = "Set a browser password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                val dialog = AlertDialog.Builder(this).setTitle("Allow browser viewing")
                    .setMessage("Use username mirror and this password in Chrome. Video is not encrypted yet. Browser controls are disabled.")
                    .setView(password).setPositiveButton("Enable", null).setNegativeButton("Cancel", null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (password.text.isEmpty()) password.error = "Enter a password"
                    else { security.setBrowserPassword(password.text.toString()); button?.text = "Allow browser viewing: On"; dialog.dismiss() }
                } }; dialog.show()
            }
        })
        content.addView(label("Camera"))
        cameras = choices(listOf("Rear", "Front"), if (front) 1 else 0) { front = it == 1; save() }
        content.addView(cameras)
        content.addView(label("Resolution"))
        resolutions = choices(listOf("720p", "1080p"), if (fullHd) 1 else 0) { fullHd = it == 1; save() }
        content.addView(resolutions)
        content.addView(label("Stream orientation"))
        val modes = listOf("landscape", "portrait", "auto")
        orientations = choices(listOf("Landscape", "Portrait", "Auto"), modes.indexOf(orientation).coerceAtLeast(0)) {
            orientation = modes[it]; mirrorPreferences().edit().putString("streamOrientation", orientation).apply()
        }
        content.addView(orientations)
        status = label("").apply { setTextIsSelectable(true) }; content.addView(status)
        content.addView(action("Battery optimization settings") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        content.addView(action("Change role") {
            stopService(Intent(this, CameraService::class.java)); LauncherActivity.changeRole(this)
        })
        clockPanel = ClockSettingsPanel(this, content)
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
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        val address = LocalNetwork.address(this)
        val metrics = CameraService.stats
        status.text = (if (address == null) "Not on Wi-Fi - connect to the same Wi-Fi as your TV"
            else "${if (CameraService.active && !CameraService.cameraRunning) "Standby at" else if (CameraService.active) "Streaming at" else "Stream address:"} http://$address:8080/video") +
            (if (CameraService.active) String.format(java.util.Locale.US,
                "\nCamera %.0f fps - Encode %.0f ms - Sending %.0f fps - %d %s",
                metrics.cameraFps, metrics.encodeMs, metrics.sendFps, metrics.clients.get(),
                if (metrics.clients.get() == 1) "viewer" else "viewers") else "") +
            (if (CameraService.active && orientation == "landscape" && CameraService.physicalPortrait) "\nTip: mount the phone sideways for a wider view" else "") +
            (CameraService.error?.let { "\n$it" } ?: "") +
            (CameraService.note?.let { "\n$it" } ?: if (CameraService.standbyFallback) "\nStandby is unavailable on this device; the camera stays running." else "")
    }
    private fun showPairing() {
        pairingDialog?.dismiss()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val code = label("", 32f); content.addView(code)
        content.addView(label("On your TV, enter this code. It is valid for 2 minutes while this screen stays open."))
        var shown: String? = security.showCode()
        val regenerate = action("Regenerate") { shown = security.showCode() }; content.addView(regenerate)
        val dialog = AlertDialog.Builder(this).setTitle("Pair new TV").setView(content).setNegativeButton("Close", null).create()
        val tick = object : Runnable {
            override fun run() {
                val lock = security.lockSeconds(); val remaining = security.remainingSeconds()
                code.text = if (lock > 0) "Too many attempts - wait ${lock}s" else if (remaining == 0L) "Code expired - Regenerate" else "$shown\nExpires in ${remaining}s"
                regenerate.isEnabled = lock == 0L
                handler.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener { security.hideCode(); handler.removeCallbacks(tick); pairingDialog = null }
        pairingDialog = dialog; dialog.show(); handler.post(tick); regenerate.requestFocus()
    }
    private fun showDevices() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        fun render() {
            content.removeAllViews()
            val devices = security.list()
            if (devices.isEmpty()) content.addView(label("No paired devices"))
            devices.forEach { device ->
                val seen = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(device.lastSeen))
                content.addView(label("${device.name}\nLast seen: $seen"))
                content.addView(action("Remove ${device.name}") { security.remove(device.hash); render() })
            }
        }
        render(); AlertDialog.Builder(this).setTitle("Paired devices").setView(scroll).setNegativeButton("Close", null).show()
    }
    override fun onResume() {
        super.onResume(); handler.post(refresh); clockPanel?.start()
        if (ClockSettings.load(this).departureEnabled) DepartureAlerts.configure(this)
        if (CameraService.active && CameraService.standbyFallback) startService(Intent(this, CameraService::class.java).setAction(CameraService.RETRY))
    }
    override fun onStop() { pairingDialog?.dismiss(); super.onStop() }
    override fun onPause() { clockPanel?.stop(); handler.removeCallbacks(refresh); super.onPause() }
}



