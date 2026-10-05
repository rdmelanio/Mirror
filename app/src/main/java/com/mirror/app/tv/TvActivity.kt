package com.mirror.app.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mirror.app.core.CameraDiscovery
import com.mirror.app.core.TvSettings
import com.mirror.app.core.dp
import com.mirror.app.core.label
import com.mirror.app.core.mirrorPreferences
import java.util.Locale

class TvActivity : Activity() {
    lateinit var settings: TvSettings
        private set
    private lateinit var feed: MirrorView
    private lateinit var light: RingLightView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var menu: QuickMenu
    private val handler = Handler(Looper.getMainLooper())
    private var client: MjpegClient? = null
    private var discovery: CameraDiscovery? = null
    @Volatile private var started = false
    @Volatile private var generation = 0
    private var lastBack = 0L
    private val hideMessage = Runnable { message.visibility = View.GONE }
    private val discoveryTimeout = Runnable {
        if (started && settings.url.isBlank()) { status.text = "Can't reach camera - open Mirror on your phone and press Start"; menu.openConnection() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = TvSettings(mirrorPreferences())
        val root = FrameLayout(this)
        feed = MirrorView(this, settings); light = RingLightView(this, settings)
        root.addView(feed, FrameLayout.LayoutParams(-1, -1)); root.addView(light, FrameLayout.LayoutParams(-1, -1))
        status = label("Connecting...", 20f).apply {
            gravity = Gravity.CENTER; setBackgroundColor(Color.argb(160, 0, 0, 0)); isFocusable = false
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        message = label("", 17f).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.argb(190, 0, 0, 0)); isFocusable = false }
        root.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        menu = QuickMenu(this)
        root.addView(menu.panel, FrameLayout.LayoutParams(dp(380), -1, Gravity.END))
        setContentView(root); refreshSettings(); immersive()
        toast("Left/Right: Mode   Up/Down: Zoom or Brightness   OK: Menu", 4000)
    }
    override fun onStart() {
        super.onStart(); started = true; feed.startFrames()
        if (settings.url.isNotBlank()) connect() else discoverFirst()
    }
    override fun onStop() {
        started = false; generation++; client?.stop(); client = null; feed.stopFrames()
        discovery?.stop(); discovery = null; menu.stopDiscovery()
        handler.removeCallbacks(discoveryTimeout); handler.removeCallbacks(hideMessage)
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    @Suppress("DEPRECATION") private fun immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun discoverFirst() {
        status.text = "Connecting..."; status.visibility = View.VISIBLE
        handler.postDelayed(discoveryTimeout, 5000)
        discovery = CameraDiscovery(this) { _, url ->
            if (started && settings.url.isBlank()) {
                settings.url = url; settings.save(); handler.removeCallbacks(discoveryTimeout)
                discovery?.stop(); discovery = null; connect()
            }
        }.also { it.start() }
    }
    fun setAddress(url: String) {
        settings.url = url; settings.save(); handler.removeCallbacks(discoveryTimeout)
        discovery?.stop(); discovery = null
        if (started) connect()
    }
    fun stopAutoDiscovery() {
        discovery?.stop(); discovery = null; handler.removeCallbacks(discoveryTimeout)
    }
    private fun connect() {
        client?.stop(); generation++
        val session = generation
        status.text = "Connecting..."; status.visibility = View.VISIBLE
        client = MjpegClient(settings.url, { bitmap ->
            if (started && generation == session) {
                feed.submit(bitmap)
                handler.post { if (started && generation == session) status.visibility = View.GONE }
            } else bitmap.recycle()
        }, { value -> handler.post {
            if (started && generation == session) { status.text = value; status.visibility = View.VISIBLE }
        } }).also { it.start() }
    }
    fun refreshSettings() { settings.save(); feed.refresh(); light.invalidate() }
    fun toast(value: String, duration: Long = 1500) {
        handler.removeCallbacks(hideMessage); message.text = value; message.visibility = View.VISIBLE
        handler.postDelayed(hideMessage, duration)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Leave system volume keys and all menu navigation to their normal handlers.
        if (event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) return super.dispatchKeyEvent(event)
        if (menu.isOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) menu.close(); return true }
            return super.dispatchKeyEvent(event)
        }
        val handled = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK)
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount == 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastBack != 0L && now - lastBack <= 2000) finish()
                    else { lastBack = now; toast("Press Back again to exit", 2000) }
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> menu.open()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                if (settings.mode == 0) {
                    settings.zoom = (settings.zoom + direction * .25f).coerceIn(1f, 3f)
                    if (settings.zoom == 1f) settings.pan = 0f
                    toast(zoomHint())
                } else { settings.light = (settings.light + direction * 10).coerceIn(10, 100); toast("Ring Light ${settings.light}%") }
                refreshSettings()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settings.mode == 0 && settings.zoom > 1f) {
                    settings.pan = (settings.pan + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) .15f else -.15f).coerceIn(-1f, 1f)
                    toast(zoomHint())
                } else { settings.mode = 1 - settings.mode; toast(if (settings.mode == 0) "MIRROR" else "RING LIGHT") }
                refreshSettings()
            }
        }
        return true
    }
    private fun zoomHint(): String = "Zoom ${String.format(Locale.US, "%.2f", settings.zoom).trimEnd('0').trimEnd('.')}x" +
        if (settings.zoom > 1f) " - Left/Right to pan, zoom to 1x to change mode" else ""
    @Deprecated("Legacy remote back handler")
    override fun onBackPressed() { if (menu.isOpen) menu.close() else super.onBackPressed() }
}
