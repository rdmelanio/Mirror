package com.mirror.app.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.widget.*
import com.mirror.app.core.*

class PhoneActivity : Activity() {
    private lateinit var controller: ClockController
    private lateinit var start: Button
    private lateinit var status: TextView
    private lateinit var nextDuty: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header = label("MIRROR / PHONE", 23f); root.addView(header)
        val wide = resources.configuration.screenWidthDp >= 600
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val preview = ClockView(this)
        controller = ClockController(this, preview, null, preview = true)
        val display = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        display.addView(preview, LinearLayout.LayoutParams(-1, if (wide) -1 else dp(280)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        start = action("START CAMERA") { if (CameraService.active) stopService(Intent(this, CameraService::class.java)) else requestStart() }
        controls.addView(start)
        controls.addView(action("CLOCK MODE") { startActivity(Intent(this, ClockActivity::class.java)) })
        controls.addView(action("SETTINGS") { startActivity(Intent(this, PhoneSettingsActivity::class.java)) })
        controls.addView(action("ROSTER LINK") { startActivity(Intent(this, com.mirror.app.phone.roster.RosterActivity::class.java)) })
        nextDuty = label("", 17f); controls.addView(nextDuty)
        controls.addView(action("Skip next PREPARE") { com.mirror.app.phone.roster.RosterAlarms.skipNext(this) })
        status = label("", 15f); controls.addView(status)
        if (wide) {
            body.addView(display, LinearLayout.LayoutParams(0, -1, 1.65f))
            body.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(display); body.addView(controls)
            root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        PhoneUi.style(root); header.setTextColor(PhoneUi.GREEN)
        setContentView(root); insetContent(root, 10); update()
    }
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
                .putExtra("front", mirrorPreferences().getBoolean("phoneFront", false)).putExtra("fullHd", mirrorPreferences().getBoolean("phoneFullHd", false)).putExtra("rotation", rotation))
        } catch (failure: Exception) { Toast.makeText(this, "Could not start: ${failure.message}", Toast.LENGTH_LONG).show() }
    }

    private fun update() {
        nextDuty.text = "NEXT DUTY\n" + com.mirror.app.phone.roster.RosterDisplay.compact(this) +
            (com.mirror.app.phone.roster.RosterDisplay.next(this)?.let { "\n" + com.mirror.app.phone.roster.RosterDisplay.legs(it) } ?: "") +
            (com.mirror.app.phone.roster.RosterStore.stale(this)?.let { "\n" + it.first } ?: "")
        nextDuty.setTextColor(com.mirror.app.phone.roster.RosterStore.stale(this)?.second ?: PhoneUi.GREEN)
        start.text = if (CameraService.active) "STOP CAMERA" else "START CAMERA"
        val metrics = CameraService.stats
        val live = metrics.clients.get() > 0
        val state = if (!CameraService.active) "CAMERA OFF" else if (live) "CAMERA LIVE" else "CAMERA READY"
        status.setTextColor(if (live) Color.RED else PhoneUi.GREEN)
        status.text = state + (if (CameraService.active) String.format(java.util.Locale.US,
            "\n%.0f fps · %.0f ms\n%d viewer%s", metrics.cameraFps, metrics.encodeMs, metrics.clients.get(), if (metrics.clients.get() == 1) "" else "s") else "\nStart the camera before Clock mode") +
            (CameraService.error?.let { "\n$it" } ?: "") + (CameraService.note?.let { "\n$it" } ?: "") +
            if (LocalNetwork.address(this) == null) "\nConnect to your TV's Wi-Fi" else ""
    }
    override fun onResume() {
        super.onResume(); controller.start(); handler.post(refresh)
        com.mirror.app.phone.roster.RosterWork.onOpen(this)
        if (ClockSettings.load(this).departureEnabled) DepartureAlerts.configure(this)
        if (CameraService.active && CameraService.standbyFallback) startService(Intent(this, CameraService::class.java).setAction(CameraService.RETRY))
    }
    override fun onPause() { controller.stop(); handler.removeCallbacks(refresh); super.onPause() }
}
