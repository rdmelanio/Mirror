package com.mirror.app.tv

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.graphics.Color
import com.mirror.app.core.action
import org.json.JSONObject
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.mirror.app.core.CameraDiscovery
import com.mirror.app.core.TvSettings
import com.mirror.app.core.dp
import com.mirror.app.core.label
import com.mirror.app.core.mirrorPreferences
import java.util.Locale

class TvActivity : ComponentActivity() {
    lateinit var settings: TvSettings
        private set
    private lateinit var feed: MirrorView
    private lateinit var light: RingLightView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var statusSpace: FrameLayout
    private lateinit var fps: TextView
    private var gear: Button? = null
    private lateinit var menu: QuickMenu
    private lateinit var root: FrameLayout
    private lateinit var paused: TextView
    private lateinit var flash: View
    private lateinit var delayBadge: TextView
    private lateinit var sleepScreen: TextView
    private val pairing by lazy { ViewerPairing(mirrorPreferences()) }
    private var pairingScreen: PairingScreen? = null
    private var pairedCamera: ViewerPairing.Camera? = null
    private var revoked = false
    private var sleeping = false
    private var wakeTouch = false
    private var wakeKey = -1
    private var lastInteraction = 0L
    private var remote: CameraRemote? = null
    var cameraSupported: Boolean? = null
        private set
    var hasFlash = false
        private set
    var torch = false
        private set
    var frozen = false
        private set
    private var minZoom = 1f
    private var maxZoom = 3f
    private var confirmedZoom = 1f
    private var pendingControls = 0
    private var probing = false
    private var lastProbe = 0L
    private var snapshotPending = false
    private var snapshotEpoch = 0
    private var longOk = false
    private val snapshots = mutableListOf<VideoFrame>()
    private var comparePanel: FrameLayout? = null
    @Volatile private var comparing = false
    private val handler = Handler(Looper.getMainLooper())
    private var client: MjpegClient? = null
    private var discovery: CameraDiscovery? = null
    @Volatile private var started = false
    @Volatile private var generation = 0
    private var lastBack = 0L
    private var failedSince = 0L
    private var discoveringSince = 0L
    private var lastSample = 0L
    private val health = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.elapsedRealtime()
            if (!sleeping && settings.autoSleep > 0 && now - lastInteraction >= settings.autoSleep * 60_000L) sleep()
            if (sleeping || pairingScreen != null || revoked) { handler.postDelayed(this, 1000); return }
            val seconds = ((now - lastSample) / 1000.0).coerceAtLeast(.001)
            lastSample = now
            val received = (client?.receivedFrames?.getAndSet(0) ?: 0) / seconds
            val drawn = feed.drawnFrames.getAndSet(0) / seconds
            fps.text = String.format(Locale.US, "Received %.0f fps · Drawn %.0f fps", received, drawn)
            if (failedSince != 0L && now - failedSince >= 10_000 &&
                (discovery == null || now - discoveringSince >= 10_000)) startAutoDiscovery()
            if (now - lastProbe >= 5000 && !probing && pendingControls == 0) probeCamera()
            handler.postDelayed(this, 1000)
        }
    }
    private val hideMessage = Runnable { message.visibility = View.GONE }
    private val discoveryTimeout = Runnable {
        if (started && settings.url.isBlank()) { status.text = "Can't reach camera - open Mirror on your phone and press Start"; menu.openConnection() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = TvSettings(mirrorPreferences())
        root = FrameLayout(this).apply {
            setOnClickListener { if (!menu.isOpen) menu.open() }
            setOnLongClickListener { if (!menu.isOpen && !comparing) toggleFreeze(); true }
        }
        feed = MirrorView(this, settings); light = RingLightView(this, settings)
        root.addView(feed, FrameLayout.LayoutParams(-1, -1)); root.addView(light, FrameLayout.LayoutParams(-1, -1))
        status = label("Connecting...", 20f).apply {
            gravity = Gravity.CENTER; setBackgroundColor(Color.argb(160, 0, 0, 0)); isFocusable = false
        }
        statusSpace = FrameLayout(this)
        statusSpace.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(statusSpace, FrameLayout.LayoutParams(-1, -1))
        message = label("", 17f).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.argb(190, 0, 0, 0)); isFocusable = false }
        statusSpace.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        fps = label("", 14f).apply { setBackgroundColor(Color.argb(140, 0, 0, 0)); isFocusable = false }
        root.addView(fps, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            gear = Button(this).apply {
                text = "⚙"; textSize = 22f; contentDescription = "Open Quick Menu"
                setTextColor(Color.WHITE); setBackgroundColor(Color.argb(100, 0, 0, 0))
                setOnClickListener { menu.open() }
            }.also { root.addView(it, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END)) }
        }
        paused = label("Paused - press OK to resume", 16f).apply {
            visibility = View.GONE; gravity = Gravity.CENTER; setBackgroundColor(Color.argb(180, 0, 0, 0))
        }
        root.addView(paused, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); visibility = View.GONE; isClickable = false }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))
        menu = QuickMenu(this)
        root.addView(menu.panel, FrameLayout.LayoutParams(dp(380), -1, Gravity.END))
        delayBadge = label("", 16f).apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); visibility = View.GONE }
        root.addView(delayBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        sleepScreen = label("Sleeping - press any button to wake", 20f).apply {
            gravity = Gravity.CENTER; setTextColor(Color.DKGRAY); setBackgroundColor(Color.BLACK); visibility = View.GONE
        }
        root.addView(sleepScreen, FrameLayout.LayoutParams(-1, -1))
        setContentView(root); refreshSettings(); immersive()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
        toast("Left/Right: Mode   Up/Down: Zoom or Brightness   OK: Menu", 4000)
    }
    override fun onStart() {
        super.onStart(); started = true; sleeping = false; sleepScreen.visibility = View.GONE; lastInteraction = SystemClock.elapsedRealtime(); feed.startFrames()
        failedSince = SystemClock.elapsedRealtime(); lastSample = failedSince
        feed.drawnFrames.set(0); handler.postDelayed(health, 1000)
        if (settings.url.isNotBlank()) connect() else discoverFirst()
    }
    override fun onStop() {
        started = false; clearPairingScreen(); revoked = false; generation++; client?.stop(); client = null; remote?.close(); remote = null; feed.stopFrames()
        frozen = false; paused.visibility = View.GONE; closeCompare(); flash.visibility = View.GONE
        discovery?.stop(); discovery = null; menu.stopDiscovery()
        handler.removeCallbacks(health); failedSince = 0L
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
        startAutoDiscovery()
    }
    private fun startAutoDiscovery() {
        discovery?.stop()
        discoveringSince = SystemClock.elapsedRealtime()
        discovery = CameraDiscovery(this) { _, url ->
            if (started && !sleeping && pairingScreen == null && !revoked && failedSince != 0L && (client == null || settings.url != url)) {
                handler.removeCallbacks(discoveryTimeout)
                connect(automatic = true, candidate = url)
            }
        }.also { it.start() }
    }
    fun setAddress(url: String) {
        clearPairingScreen(); revoked = false; settings.cameraId = ""; pairedCamera = null
        settings.url = url; settings.save(); handler.removeCallbacks(discoveryTimeout)
        discovery?.stop(); discovery = null
        if (started) connect()
    }
    fun stopAutoDiscovery() {
        discovery?.stop(); discovery = null; handler.removeCallbacks(discoveryTimeout)
    }
    private fun connect(automatic: Boolean = false, candidate: String = settings.url) {
        if (!started || sleeping) return
        client?.stop(); client = null; remote?.close(); remote = null; generation++
        val session = generation; val address = candidate
        status.text = "Connecting..."; status.visibility = View.VISIBLE
        Thread({
            try {
                val camera = pairing.hello(address)
                handler.post {
                    if (!started || sleeping || generation != session) return@post
                    if (automatic && (camera == null || settings.cameraId.isNotEmpty() && camera.id != settings.cameraId)) {
                        failedSince = SystemClock.elapsedRealtime(); return@post
                    }
                    if (camera == null && settings.cameraId.isNotEmpty()) {
                        connectionFailed(session); return@post
                    }
                    settings.url = address; settings.save()
                    pairedCamera = camera
                    if (camera != null) {
                        settings.cameraId = camera.id; settings.save()
                        val token = pairing.token(camera.id)
                        if (token == null) { showPairing(camera); return@post }
                        startStream(session, SourceAuth(token))
                    } else startStream(session, SourceAuth(username = settings.username, password = settings.password))
                }
            } catch (_: Exception) { handler.post { connectionFailed(session) } }
        }, "Mirror-identify-camera").start()
    }
    private fun connectionFailed(session: Int) {
        if (!started || sleeping || generation != session) return
        status.text = "Can't reach camera - open Mirror on your phone and press Start"; status.visibility = View.VISIBLE
        if (failedSince == 0L) failedSince = SystemClock.elapsedRealtime()
        handler.postDelayed({ if (started && !sleeping && generation == session && pairingScreen == null && !revoked) connect() }, 3000)
    }
    private fun startStream(session: Int, auth: SourceAuth) {
        cameraSupported = null; hasFlash = false; torch = false; pendingControls = 0
        snapshotPending = false; probing = false; settings.pan = 0f; feed.opticalZoom = true
        if (frozen) toggleFreeze()
        closeCompare(); menu.refreshCameraControls()
        val unauthorized = { handler.post { if (started && generation == session) unpaired() }; Unit }
        remote = CameraRemote(settings.url, auth, unauthorized); probeCamera()
        status.text = if (auth.token != null) "Waking camera..." else "Connecting..."; status.visibility = View.VISIBLE
        client = MjpegClient(settings.url, { frame ->
            if (started && generation == session) feed.submit(frame) else frame.release()
        }, { value -> handler.post {
            if (started && generation == session) {
                status.text = value; status.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                if (value.isEmpty()) { failedSince = 0L; stopAutoDiscovery() }
                else if (failedSince == 0L) failedSince = SystemClock.elapsedRealtime()
            }
        } }, { started && generation == session && !comparing && feed.ready() }, auth, unauthorized, { value -> handler.post {
            if (started && generation == session) { delayBadge.text = value; delayBadge.visibility = if (value.isBlank()) View.GONE else View.VISIBLE }
        } }).also { it.configureDelay(if (settings.mode == 2) settings.delay else 0); it.start() }
    }
    private fun clearPairingScreen() { pairingScreen?.let { root.removeView(it) }; pairingScreen = null }
    private fun showPairing(camera: ViewerPairing.Camera) {
        if (!started || sleeping) return
        clearPairingScreen(); menu.close(); stopAutoDiscovery(); failedSince = 0L; revoked = false
        val session = generation; val address = settings.url
        val screen = PairingScreen(this, camera.name, { code ->
            Thread({
                try {
                    pairing.pair(address, camera, code, android.os.Build.MODEL)
                    handler.post { if (started && generation == session && pairingScreen != null) { clearPairingScreen(); connect() } }
                } catch (error: Exception) { handler.post {
                    if (started && generation == session) pairingScreen?.error(error.message ?: "Pairing failed")
                } }
            }, "Mirror-pair-TV").start()
        }, { clearPairingScreen(); menu.openConnection() })
        pairingScreen = screen; root.addView(screen, FrameLayout.LayoutParams(-1, -1))
    }
    private fun unpaired() {
        val camera = pairedCamera ?: return
        pairing.forget(camera.id); generation++; client?.stop(); client = null; remote?.close(); remote = null
        feed.stopFrames(); feed.startFrames(); stopAutoDiscovery(); menu.close(); closeCompare()
        frozen = false; paused.visibility = View.GONE; delayBadge.visibility = View.GONE
        revoked = true; failedSince = 0L
        status.text = "This TV is no longer paired - press OK to pair again"; status.visibility = View.VISIBLE
        root.setOnClickListener { if (revoked) showPairing(camera) else if (!menu.isOpen) menu.open() }
    }
    private fun sleep() {
        sleeping = true; generation++; client?.stop(); client = null; remote?.close(); remote = null
        feed.stopFrames(); stopAutoDiscovery(); menu.close(); closeCompare(); clearPairingScreen()
        frozen = false; paused.visibility = View.GONE; delayBadge.visibility = View.GONE
        sleepScreen.visibility = View.VISIBLE
    }
    private fun wake() {
        sleeping = false; lastInteraction = SystemClock.elapsedRealtime(); sleepScreen.visibility = View.GONE
        feed.startFrames(); failedSince = lastInteraction
        if (revoked) { status.text = "This TV is no longer paired - press OK to pair again"; status.visibility = View.VISIBLE }
        else if (settings.url.isNotBlank()) connect() else discoverFirst()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastInteraction = SystemClock.elapsedRealtime()
            if (sleeping) { wakeTouch = true; wake() }
        }
        if (wakeTouch) { if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) wakeTouch = false; return true }
        return super.dispatchTouchEvent(event)
    }
    fun userActivity() { lastInteraction = SystemClock.elapsedRealtime() }
    fun trackDialog(dialog: android.app.Dialog) {
        val window = dialog.window ?: return
        val callback = window.callback
        window.callback = object : android.view.Window.Callback by callback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean { userActivity(); return callback.dispatchKeyEvent(event) }
            override fun dispatchTouchEvent(event: MotionEvent): Boolean { userActivity(); return callback.dispatchTouchEvent(event) }
        }
    }
    fun menuVisibilityChanged(open: Boolean) {
        statusSpace.setPadding(0, 0, if (open) dp(380) else 0, 0)
        gear?.visibility = if (open) View.GONE else View.VISIBLE
    }
    fun refreshSettings() {
        settings.save(); client?.configureDelay(if (settings.mode == 2) settings.delay else 0); feed.refresh(); light.invalidate()
        fps.visibility = if (settings.showFps) View.VISIBLE else View.GONE
    }
    fun toast(value: String, duration: Long = 1500) {
        handler.removeCallbacks(hideMessage); message.text = value; message.visibility = View.VISIBLE
        handler.postDelayed(hideMessage, duration)
    }
    private fun probeCamera() {
        val endpoint = remote ?: return
        val session = generation
        probing = true; lastProbe = SystemClock.elapsedRealtime()
        endpoint.probe { json, supported -> handler.post {
            if (started && generation == session) {
                probing = false
                if (supported != null) {
                    if (pairedCamera != null && json != null && !json.optBoolean("streaming") && !supported) {
                        lastProbe = 0L; return@post
                    }
                    cameraSupported = supported; feed.opticalZoom = supported
                    if (json != null && supported) applyCameraStatus(json)
                    else { minZoom = 1f; maxZoom = 3f; settings.zoom = settings.zoom.coerceIn(1f, 3f) }
                    refreshSettings(); menu.refreshCameraControls()
                }
            }
        } }
    }
    private fun applyCameraStatus(json: JSONObject) {
        minZoom = json.optDouble("minZoom", 1.0).toFloat()
        maxZoom = json.optDouble("maxZoom", 1.0).toFloat().coerceAtLeast(minZoom)
        confirmedZoom = json.optDouble("zoom", 1.0).toFloat().coerceIn(minZoom, maxZoom)
        if (pendingControls == 0) settings.zoom = confirmedZoom
        hasFlash = json.optBoolean("hasFlash"); torch = json.optBoolean("torch")
        if (settings.zoom <= 1f) settings.pan = 0f
    }
    private fun cameraAction(query: String, done: (Boolean) -> Unit = {}) {
        if (cameraSupported != true) {
            toast(if (cameraSupported == null) "Checking camera controls..." else "Camera controls unavailable for this source")
            done(false); return
        }
        val endpoint = remote ?: run { done(false); return }
        val session = generation
        pendingControls++
        endpoint.control(query) { json, error, unsupported -> handler.post {
            if (started && generation == session) {
                pendingControls--
                if (unsupported) { cameraSupported = false; feed.opticalZoom = false; minZoom = 1f; maxZoom = 3f }
                if (json != null) applyCameraStatus(json)
                else if (pendingControls == 0 && !unsupported) settings.zoom = confirmedZoom
                if (error != null) toast(error, 3000)
                refreshSettings(); menu.refreshCameraControls(); done(error == null)
            }
        } }
    }
    fun zoomBy(direction: Int) {
        if (cameraSupported == null) { toast("Checking camera controls..."); return }
        val ratio = (settings.zoom + direction * .25f).coerceIn(minZoom, maxZoom)
        settings.zoom = ratio
        if (ratio <= 1f) settings.pan = 0f
        if (cameraSupported == true) cameraAction("zoom=$ratio") { success -> if (success) toast(zoomHint()) }
        toast(zoomHint()); refreshSettings()
    }
    fun resetZoom() {
        settings.pan = 0f
        if (cameraSupported == null) { toast("Checking camera controls..."); return }
        settings.zoom = 1f.coerceIn(minZoom, maxZoom)
        if (cameraSupported == true) cameraAction("zoom=${settings.zoom}") { success -> if (success) toast(zoomHint()) }
        toast(zoomHint()); refreshSettings()
    }
    fun toggleTorch() { cameraAction("torch=${if (torch) "off" else "on"}") { if (it) toast(if (torch) "Flashlight on" else "Flashlight off") } }
    fun focusCenter() { cameraAction("focus=center") { if (it) toast("Focused at center") } }
    fun toggleFreeze() {
        if (!feed.setFrozen(!frozen)) { toast("Waiting for a camera frame"); return }
        frozen = !frozen; paused.visibility = if (frozen) View.VISIBLE else View.GONE
        menu.refreshCameraControls()
    }
    fun takeSnapshot() {
        if (snapshotPending) { toast("Saving snapshot..."); return }
        if (cameraSupported != true) { toast("Snapshot requires a Mirror phone camera"); return }
        val copy = try { feed.snapshot() } catch (_: OutOfMemoryError) { null }
        if (copy == null) { toast("Waiting for a camera frame"); return }
        snapshotPending = true
        val epoch = snapshotEpoch
        cameraAction("action=snapshot") { success ->
            snapshotPending = false
            if (success) {
                if (epoch == snapshotEpoch) {
                    if (snapshots.size == 3) snapshots.removeAt(0)
                    snapshots.add(copy)
                }
                flash.animate().cancel(); flash.alpha = 1f; flash.visibility = View.VISIBLE
                flash.animate().alpha(0f).setDuration(180).withEndAction { flash.visibility = View.GONE }.start()
                toast("Saved to phone gallery", 2500)
            }
        }
    }
    fun clearSnapshots() { snapshotEpoch++; closeCompare(); snapshots.clear(); toast("Snapshots cleared") }
    fun showCompare() {
        if (snapshots.isEmpty()) { toast("Take a snapshot first"); return }
        menu.close(); closeCompare(); comparing = true
        val view = CompareView(this, snapshots.toList(), settings.flip)
        val panel = FrameLayout(this)
        panel.addView(view, FrameLayout.LayoutParams(-1, -1))
        val back = action("Back to live mirror") { closeCompare() }
        panel.addView(back, FrameLayout.LayoutParams(dp(220), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1)); comparePanel = panel; view.requestFocus()
    }
    private fun closeCompare() { comparePanel?.let { root.removeView(it) }; comparePanel = null; comparing = false }
    override fun onDestroy() { snapshots.clear(); remote?.close(); super.onDestroy() }
    // AndroidX core marks its bridge RestrictedApi, but this is Activity's public
    // platform callback; intercepting before focused Views is required for remote OK.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == wakeKey) {
            if (event.action == KeyEvent.ACTION_UP) wakeKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInteraction = SystemClock.elapsedRealtime()
            if (sleeping) {
                wake()
                if (event.keyCode !in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
                    wakeKey = event.keyCode; return true
                }
            }
            if (revoked && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                pairedCamera?.let { showPairing(it) }; return true
            }
        }
        if (pairingScreen != null || revoked) return super.dispatchKeyEvent(event)
        // Route live controls before a touch gear/root can consume remote OK.
        // Menu and Compare retain normal focus navigation; volume passes to Android.
        if (!menu.isOpen && !comparing && event.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            return event.dispatch(this, window.decorView.keyDispatcherState, this)
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Focused menu controls handle touch/D-pad normally; AndroidX owns Back on every API.
        if (pairingScreen != null || revoked || menu.isOpen || comparing) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (event.repeatCount == 0) toggleFreeze()
            return true
        }
        if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.repeatCount == 0) { longOk = false; event.startTracking() }
            return true
        }
        val handled = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU)
        if (!handled) return super.onKeyDown(keyCode, event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> menu.open()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                if (settings.mode == 0) {
                    zoomBy(direction)
                } else if (settings.mode == 2) {
                    val options = listOf(3, 5, 10, 15)
                    settings.delay = options[(options.indexOf(settings.delay) + direction).coerceIn(0, options.lastIndex)]
                    toast("Delayed ${settings.delay}s")
                } else { settings.light = (settings.light + direction * 10).coerceIn(10, 100); toast("Ring Light ${settings.light}%") }
                refreshSettings()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settings.mode == 0 && settings.zoom > 1f) {
                    settings.pan = (settings.pan + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) .15f else -.15f).coerceIn(-1f, 1f)
                    toast(zoomHint())
                } else {
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    settings.mode = (settings.mode + direction + 3) % 3
                    toast(listOf("MIRROR", "RING LIGHT", "DELAYED")[settings.mode])
                }
                refreshSettings()
            }
        }
        return true
    }
    private fun zoomHint(): String = "Zoom ${String.format(Locale.US, "%.2f", settings.zoom).let { if (it.endsWith("00")) it.dropLast(1) else it.trimEnd('0') }}x" +
        if (settings.zoom > 1f) " - Left/Right to pan, zoom to 1x to change mode" else ""
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (pairingScreen == null && !revoked && !menu.isOpen && !comparing && keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            longOk = true; toggleFreeze(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pairingScreen == null && !revoked && !menu.isOpen && !comparing && keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (!longOk && !event.isCanceled) { if (frozen) toggleFreeze() else menu.open() }
            longOk = false; return true
        }
        return super.onKeyUp(keyCode, event)
    }
    private fun handleBack() {
        if (sleeping) { wake(); return }
        if (pairingScreen != null) { clearPairingScreen(); menu.openConnection(); return }
        if (comparing) { closeCompare(); lastBack = 0L; return }
        if (menu.isOpen) { menu.close(); lastBack = 0L; return }
        val now = SystemClock.elapsedRealtime()
        if (lastBack != 0L && now - lastBack <= 2000) finish()
        else { lastBack = now; toast("Press Back again to exit", 2000) }
    }
}


