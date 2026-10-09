package com.mirror.app.phone

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.hardware.*
import android.os.*
import android.service.dreams.DreamService
import android.view.*
import com.mirror.app.core.insetContent

/** Updated only by service state changes, never by the encoder. Main-thread listeners. */
object ClockMirrorState {
    var state = "CAMERA OFF"; private set
    var ready = false; private set
    var lastViewed = Long.MIN_VALUE; private set
    val listeners = mutableSetOf<() -> Unit>()
    fun update(active: Boolean, waiting: Boolean, live: Boolean) {
        if (live) lastViewed = SystemClock.elapsedRealtime()
        val next = if (active && live) "LIVE" else if (active && waiting) "STANDBY" else "CAMERA OFF"
        val changed = next != state || ready != active
        state = next; ready = active
        if (changed) listeners.toList().forEach { it() }
    }
}

/** Visible-host lifecycle owns all scheduling and sensor callbacks. No additional wake locks. */
class ClockController(private val context: Context, private val view: ClockView, private val window: Window?, private val preview: Boolean = false) : SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var policy = ClockLightPolicy()
    private var running = false
    private val calendar: ClockCalendar = ClockCalendar(context.applicationContext) {
        view.roster = calendarSnapshot()
        if (running) render()
    }
    private fun calendarSnapshot(): ClockCalendar.Snapshot = if (DepartureRosterSource.select(context, view.settings) == DepartureSourcePolicy.Source.ECREW) DepartureRosterSource.snapshot(context) else calendar.snapshot
    fun refreshCalendar() { if (DepartureRosterSource.select(context, view.settings) == DepartureSourcePolicy.Source.ECREW) render() else calendar.refresh(true) }
    private val tick = Runnable { render() }
    private val deadline = Runnable { render() }
    private val stateChanged: () -> Unit = { if (running) { applyLight(); render() } }
    private val alertChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (running && !preview && key == "active") { view.stopInteraction(); render() }
    }
    private val rosterChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (running && key in setOf("changeBanner", "lastSuccess", "linked")) { calendar.start(view.settings); render() }
    }
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "settings" && running) { view.settings = ClockSettings.load(context); calendar.start(view.settings); render() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (running) { calendar.timeChanged(); render() } }
    }
    fun start() {
        com.mirror.app.phone.roster.EcrewFirefox.foregroundOpened(context, view)
        com.mirror.app.phone.roster.RosterWork.onOpen(context)
        if (running) return
        running = true; policy = ClockLightPolicy(); view.settings = ClockSettings.load(context)
        ClockSettings.prefs(context).registerOnSharedPreferenceChangeListener(preferencesChanged)
        com.mirror.app.phone.roster.RosterStore.prefs(context).registerOnSharedPreferenceChangeListener(rosterChanged)
        ClockMirrorState.listeners.add(stateChanged)
        view.showDepartureAlerts = !preview
        if (!preview) {
            DepartureAlerts.prefs(context).registerOnSharedPreferenceChangeListener(alertChanged)
            if (view.settings.departureEnabled) DepartureAlerts.configure(context)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter) }
        if (!preview) sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        calendar.start(view.settings); view.roster = calendarSnapshot()
        render()
    }
    fun stop() {
        if (!running) return
        com.mirror.app.phone.roster.EcrewFirefox.foregroundClosed(view)
        running = false; view.saveSize(); view.stopInteraction(); calendar.stop(); sensors.unregisterListener(this); handler.removeCallbacksAndMessages(null)
        ClockSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        DepartureAlerts.prefs(context).unregisterOnSharedPreferenceChangeListener(alertChanged)
        com.mirror.app.phone.roster.RosterStore.prefs(context).unregisterOnSharedPreferenceChangeListener(rosterChanged)
        ClockMirrorState.listeners.remove(stateChanged); context.unregisterReceiver(receiver)
    }
    private fun render() {
        if (!running) return
        handler.removeCallbacks(tick)
        view.roster = calendarSnapshot()
        view.mirrorState = ClockMirrorState.state
        applyLight(); view.invalidate()
        ClockWeather.refresh(context, view.settings) { if (running) view.invalidate() }
        val alert = if (!preview) DepartureAlerts.active(context) else null
        val rosterAlert = com.mirror.app.phone.roster.RosterStore.phone(context) && com.mirror.app.phone.roster.RosterChanges.state(context).visible
        val interval = if (rosterAlert) 500L else if (alert?.kind == DeparturePlan.Kind.WARNING || (!view.blank && (view.settings.seconds || view.settings.drift ||
            (view.settings.status && view.mirrorState == "LIVE")))) 1000L else 60_000L
        val now = System.currentTimeMillis()
        val boundary = if (DepartureRosterSource.select(context, view.settings) == DepartureSourcePolicy.Source.ECREW) ClockEcrewRoster.nextBoundary(com.mirror.app.phone.roster.RosterStore.load(context), now) else ClockRoster.nextBoundary(view.roster.duties, now)
        handler.postDelayed(tick, minOf(interval - now % interval, (boundary - now).coerceAtLeast(1)))
        scheduleDeadline()
    }
    private fun applyLight() {
        val now = SystemClock.elapsedRealtime(); val live = ClockMirrorState.state == "LIVE"
        policy.advance(now, live, ClockMirrorState.lastViewed)
        val night = !preview && view.settings.autoNight && policy.night
        val blank = !preview && policy.blank(view.settings, now)
        if (night != view.night || blank != view.blank) { view.night = night; view.blank = blank; view.invalidate() }
        if (!preview) window?.let {
            val alert = DepartureAlerts.active(context)
            val brightness = if (alert != null) minOf(0.35f, maxOf(0.08f, view.settings.maxBrightness / 100f)) else policy.brightness(view.settings, live, now)
            if (kotlin.math.abs(it.attributes.screenBrightness - brightness) >= 0.002f) {
                it.attributes = it.attributes.apply { screenBrightness = brightness }
            }
        }
    }
    private fun scheduleDeadline() {
        handler.removeCallbacks(deadline)
        if (!preview) policy.nextDeadline(SystemClock.elapsedRealtime(), view.settings)?.let {
            handler.postDelayed(deadline, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        val wasBlank = view.blank
        policy.sample(event.values[0], SystemClock.elapsedRealtime()); applyLight(); scheduleDeadline()
        if (wasBlank != view.blank) render()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Suppress("DEPRECATION")
private fun immersive(window: Window) {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

class ClockActivity : androidx.activity.ComponentActivity() {
    private lateinit var controller: ClockController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else { @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) }
        immersive(window)
        val clock = ClockView(this) { moveTaskToBack(true); finish() }
        setContentView(clock); controller = ClockController(this, clock, window)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Hold to exit on buttons and gestures alike. */ }
        })
    }
    private fun wakeForDeparture() {
        if (intent.getBooleanExtra("departure", false) && DepartureAlerts.active(this) != null) {
            if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true)
            else { @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); wakeForDeparture() }
    override fun onResume() { super.onResume(); wakeForDeparture(); immersive(window); controller.start() }
    override fun onPause() { controller.stop(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(window) }
}

class MirrorClockDream : DreamService() {
    private var controller: ClockController? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); isInteractive = true; isFullscreen = true; isScreenBright = true
        immersive(window)
        val clock = ClockView(this) { finish() }
        setContentView(clock); controller = ClockController(this, clock, window)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
    override fun onDreamingStarted() { super.onDreamingStarted(); controller?.start() }
    override fun onDreamingStopped() { controller?.stop(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { controller?.stop(); controller = null; super.onDetachedFromWindow() }
}

class ClockSettingsActivity : PhoneSettingsActivity()

