package com.mirror.app.tv

import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import com.mirror.app.BuildConfig
import com.mirror.app.core.CameraDiscovery
import com.mirror.app.core.LauncherActivity
import com.mirror.app.core.StreamAddress
import com.mirror.app.core.action
import com.mirror.app.core.dp
import com.mirror.app.core.label

class QuickMenu(private val activity: TvActivity) {
    private val settings get() = activity.settings
    val panel = ScrollView(activity).apply { visibility = View.GONE; setBackgroundColor(Color.argb(235, 18, 23, 31)); isFillViewport = true }
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16)) }
    private val first: Button
    private lateinit var address: EditText
    private lateinit var find: Button
    private lateinit var results: LinearLayout
    private var discovery: CameraDiscovery? = null
    private val seen = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val notFound = Runnable {
        if (isOpen && seen.isEmpty()) { results.removeAllViews(); results.addView(activity.label("No camera found. Open Mirror on your phone and press Start, then try again.")) }
    }
    val isOpen get() = panel.visibility == View.VISIBLE
    private val refreshers = mutableListOf<() -> Unit>()
    init {
        panel.addView(content)
        first = activity.action("Close menu") { close() }; content.addView(first)
        heading("Mode")
        option("Mode", listOf("MIRROR", "RING LIGHT"), { settings.mode }) { settings.mode = it }
        heading("Camera")
        val torchButton = activity.action("") { activity.toggleTorch() }
        val focusButton = activity.action("Focus center") { activity.focusCenter() }
        val snapshotButton = activity.action("Take snapshot") { activity.takeSnapshot() }
        val freezeButton = activity.action("") { activity.toggleFreeze() }
        val cameraRefresh = {
            torchButton.text = "Flashlight: ${if (activity.torch) "On" else "Off"}"
            torchButton.visibility = if (activity.cameraSupported == true && activity.hasFlash) View.VISIBLE else View.GONE
            focusButton.isEnabled = activity.cameraSupported == true
            snapshotButton.isEnabled = activity.cameraSupported == true
            freezeButton.text = if (activity.frozen) "Unfreeze" else "Freeze"
            Unit
        }
        refreshers.add(cameraRefresh); cameraRefresh()
        content.addView(torchButton); content.addView(focusButton)
        content.addView(activity.action("Zoom +") { activity.zoomBy(1) })
        content.addView(activity.action("Zoom -") { activity.zoomBy(-1) })
        content.addView(activity.action("Zoom reset") { activity.resetZoom() })
        content.addView(snapshotButton); content.addView(freezeButton)
        content.addView(activity.action("Compare") { activity.showCompare() })
        content.addView(activity.action("Clear snapshots") { activity.clearSnapshots() })
        heading("Ring Light")
        option("Shape", listOf("Frame", "Ring", "Soft gradient"), { settings.shape }) { settings.shape = it }
        option("Size", listOf("Small (15%)", "Medium (25%)", "Large (35%)"), { settings.size }) { settings.size = it }
        option("Color temperature", listOf("Warm", "Neutral", "Cool"), { settings.temperature }) { settings.temperature = it }
        slider("Light brightness", 10, 100, 10, { settings.light }) { settings.light = it }
        heading("Filters")
        slider("Brightness", -50, 50, 5, { settings.brightness }) { settings.brightness = it }
        slider("Contrast", -50, 50, 5, { settings.contrast }) { settings.contrast = it }
        slider("Saturation", -50, 50, 5, { settings.saturation }) { settings.saturation = it }
        slider("Warmth", -50, 50, 5, { settings.warmth }) { settings.warmth = it }
        if (Build.VERSION.SDK_INT >= 31) option("Soft focus", listOf("Off", "Low", "Medium"), { settings.softFocus }) { settings.softFocus = it }
        content.addView(activity.action("Reset filters") { settings.resetFilters(); activity.refreshSettings(); refreshControls(); activity.toast("Filters reset") })
        heading("Display")
        option("Mirror flip", listOf("Off", "On"), { if (settings.flip) 1 else 0 }) { settings.flip = it == 1 }
        option("Scaling", listOf("Fit", "Fill / crop"), { if (settings.fill) 1 else 0 }) { settings.fill = it == 1 }
        option("Rotation", listOf("0°", "90°", "180°", "270°"), { settings.rotation / 90 }) { settings.rotation = it * 90 }
        heading("Connection")
        address = EditText(activity).apply {
            textSize = 16f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            hint = "e.g. 192.168.1.23:8080"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "Camera stream address"
        }
        content.addView(address, LinearLayout.LayoutParams(-1, activity.dp(56)))
        content.addView(activity.action("Connect") {
            try { val url = StreamAddress.normalize(address.text.toString()); activity.setAddress(url); close() }
            catch (error: Exception) { activity.toast(error.message ?: "Invalid address", 3000); address.requestFocus() }
        })
        find = activity.action("Find camera automatically") { findCameras() }; content.addView(find)
        results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }; content.addView(results)
        heading("Change role")
        content.addView(activity.action("Change role") { stopDiscovery(); LauncherActivity.changeRole(activity) })
        heading("About")
        option("Show FPS", listOf("Off", "On"), { if (settings.showFps) 1 else 0 }) { settings.showFps = it == 1 }
        content.addView(activity.label("Mirror ${BuildConfig.VERSION_NAME}"))
    }
    private fun heading(title: String) { content.addView(activity.label(title, 23f)) }
    private fun option(title: String, choices: List<String>, read: () -> Int, write: (Int) -> Unit) {
        lateinit var button: Button
        button = activity.action("") {
            AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(choices.toTypedArray(), read()) { dialog, index ->
                write(index); activity.refreshSettings(); refreshControls(); dialog.dismiss(); button.requestFocus()
            }.setNegativeButton("Cancel", null).show()
        }
        val refresh = { button.text = "$title: ${choices[read()]}"; Unit }
        refreshers.add(refresh); refresh(); content.addView(button)
    }
    private fun slider(title: String, min: Int, max: Int, step: Int, read: () -> Int, write: (Int) -> Unit) {
        val label = activity.label("")
        val bar = SeekBar(activity).apply { this.max = max - min; progress = read() - min; contentDescription = title; isFocusable = true; minimumHeight = activity.dp(48) }
        val refresh = { label.text = "$title: ${read()}"; bar.progress = read() - min; Unit }
        refreshers.add(refresh); refresh()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { write((min + ((progress + step / 2) / step) * step).coerceIn(min, max)); activity.refreshSettings(); refresh() }
            }
        })
        bar.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) { write((read() + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(min, max)); activity.refreshSettings(); refresh() }
                true
            } else false
        }
        content.addView(label); content.addView(bar, LinearLayout.LayoutParams(-1, activity.dp(48)))
    }
    fun refreshCameraControls() { refreshControls() }
    private fun refreshControls() { refreshers.forEach { it() } }
    fun open() {
        if (isOpen) return
        refreshControls(); address.setText(settings.url)
        panel.animate().cancel(); panel.visibility = View.VISIBLE; activity.menuVisibilityChanged(true); panel.translationX = activity.dp(380).toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
        panel.post { panel.scrollTo(0, 0); first.requestFocus() }
    }
    fun openConnection() {
        open()
        panel.post { find.requestFocus(); panel.smoothScrollTo(0, address.top) }
    }
    fun close() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
        stopDiscovery(); panel.animate().cancel()
        panel.visibility = View.GONE; panel.translationX = 0f
        activity.menuVisibilityChanged(false)
    }
    private fun findCameras() {
        stopDiscovery(); seen.clear(); results.removeAllViews()
        results.addView(activity.label("Looking for cameras..."))
        discovery = CameraDiscovery(activity) { name, url ->
            if (isOpen && seen.add(url)) {
                if (seen.size == 1) results.removeAllViews()
                results.addView(activity.action("$name\n$url") { activity.setAddress(url); close() })
            }
        }.also { it.start() }
        handler.postDelayed(notFound, 5000)
    }
    fun stopDiscovery() { discovery?.stop(); discovery = null; handler.removeCallbacks(notFound) }
}

