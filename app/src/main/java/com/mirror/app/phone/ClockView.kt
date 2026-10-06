package com.mirror.app.phone

import android.app.AlarmManager
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.roundToInt
import com.mirror.app.R
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

/** No animation loop. Hosts schedule second/minute updates; every element shares one transform. */
class ClockView(context: Context, private val exit: (() -> Unit)? = null) : View(context) {
    var settings = ClockSettings.load(context)
        set(value) { field = value; invalidate() }
    var night = false
    var blank = false
    var mirrorState = "CAMERA OFF"
    var roster = ClockCalendar.Snapshot()
        set(value) { field = value; invalidate() }
    private val regular = resources.getFont(R.font.b612_regular)
    private val bold = resources.getFont(R.font.b612_bold)
    private val mono = resources.getFont(R.font.b612_mono)
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shiftMinute = Long.MIN_VALUE
    private var shiftX = 0f; private var shiftY = 0f
    private var hint = false
    private var holding = false
    private var downX = 0f; private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var gestureSize = settings.sizePercent.toFloat()
    private var sizeChanged = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelHold(); hint = false; gestureSize = settings.sizePercent.toFloat()
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gestureSize = (gestureSize * detector.scaleFactor).coerceIn(40f, 100f)
            val percent = gestureSize.roundToInt()
            if (percent != settings.sizePercent) {
                settings = settings.copy(sizePercent = percent); sizeChanged = true
            }
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { saveSize() }
    }).apply { isQuickScaleEnabled = false }
    private fun cancelHold() { holding = false; removeCallbacks(held) }
    internal fun saveSize() {
        if (sizeChanged) {
            // Merge only size; never overwrite settings changed by another visible host.
            ClockSettings.load(context).copy(sizePercent = settings.sizePercent).save(context)
            sizeChanged = false
        }
    }
    private val hideHint = Runnable { hint = false; invalidate() }
    private val held = Runnable { if (holding) { holding = false; exit?.invoke() } }
    init { setBackgroundColor(Color.BLACK); isClickable = true; contentDescription = "Mirror clock. Pinch to resize. Hold anywhere for two seconds to exit." }
    private data class Row(val text: String, val size: Float, val face: Typeface, val label: String = "", val digits: Boolean = false, val indicator: Boolean = false, val hintRow: Boolean = false, val color: Int? = null)
    private fun format(pattern: String, date: Date, utc: Boolean = false): String = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
        timeZone = if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }.format(date)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blank || width == 0 || height == 0) return
        val s = settings; val now = Date()
        val use24 = s.hourFormat == "24-hour" || (s.hourFormat == "System" && DateFormat.is24HourFormat(context))
        val localPattern = (if (use24) "HH:mm" else "hh:mm") + if (s.seconds) ":ss" else ""
        val local = format(localPattern, now) + if (use24) "" else format(" a", now)
        val utc = format(if (s.seconds) "HHmmss'Z'" else "HHmm'Z'", now, true)
        val primary = if (s.usesUtc) utc else local
        val rows = mutableListOf<Row>()
        when (s.style) {
            "Cockpit" -> {
                rows += Row(primary, 100f, mono, if (s.usesUtc) "UTC" else "LOC", true)
                if (s.showUtc) rows += Row(if (s.usesUtc) local else utc, 54f, mono, if (s.usesUtc) "LOC" else "UTC", true)
            }
            "Stacked" -> {
                rows += Row(format(if (s.usesUtc || use24) "HH" else "hh", now, s.usesUtc), 158f, bold, digits = true)
                rows += Row(format("mm", now, s.usesUtc), 158f, bold, digits = true)
                if (s.seconds) rows += Row(format("ss", now, s.usesUtc), 38f, regular, "SEC", true)
                if (s.usesUtc || !use24) rows += Row(if (s.usesUtc) "UTC" else format("a", now), 22f, regular)
            }
            "Word clock" -> {
                val calendar = Calendar.getInstance(if (s.usesUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
                val words = ClockWords.time(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)).split(" ")
                var line = ""
                words.forEach { word ->
                    if ((line + " " + word).length > 17) { rows += Row(line, 48f, regular, digits = true); line = word }
                    else line = if (line.isEmpty()) word else "$line $word"
                }
                rows += Row(line, 48f, regular, digits = true)
                if (s.seconds) rows += Row(format("ss", now), 28f, regular, "SEC", true)
                if (s.usesUtc) rows += Row("UTC", 22f, regular)
            }
            else -> rows += Row(primary, 112f, if (s.minimalFont == "B612") regular else thin, digits = true)
        }
        if (s.date) rows += Row(format("EEE dd MMM", now, s.usesUtc).uppercase(Locale.ENGLISH), 25f, regular, color = s.dateColor)
        if (s.alarm) context.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
            rows += Row(format(if (use24) "HH:mm" else "hh:mm a", Date(it.triggerTime)), 24f, regular, "ALARM", color = s.alarmColor)
        }
        if (s.weather && s.city.isNotBlank()) rows += Row(ClockWeather.display(context, s), 24f, regular, color = s.weatherColor)
        if (s.schedule) {
            val titleFormat = java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH)
            fun addSchedule(text: String, size: Float = 22f) { rows += Row(text, size, regular, color = s.scheduleColor) }
            if (!ClockCalendar.hasPermission(context)) addSchedule("Allow calendar access in Clock settings")
            else if (roster.calendarId != s.calendarId || roster.loading) addSchedule("Loading calendar…")
            else if (roster.checkedAt == 0L) addSchedule(roster.error ?: "Choose a roster calendar")
            else {
                val today = java.time.Instant.ofEpochMilli(now.time).atZone(ClockRoster.zone).toLocalDate()
                ClockRoster.days(roster.duties, now.time).forEach { day ->
                    addSchedule("${day.label} · ${titleFormat.format(day.date).uppercase(Locale.ENGLISH)} · PHT", 19f)
                    val dayStart = day.date.atStartOfDay(ClockRoster.zone).toInstant().toEpochMilli()
                    if (dayStart !in roster.windowStart until roster.windowEnd) addSchedule("Calendar needs refreshing")
                    else if (day.duties.isEmpty()) {
                        val label = if (day.date == today.plusDays(1)) "Tomorrow" else if (day.date == today) "Today" else titleFormat.format(day.date)
                        addSchedule("$label: no calendar entry")
                    } else day.duties.forEach { duty ->
                        val overnight = if (duty.day < today) " · from ${titleFormat.format(duty.day)}" else ""
                        addSchedule("${duty.text} · ${ClockRoster.range(duty, use24)}$overnight")
                        if (duty.calendarTimes) addSchedule("Calendar times · report/debrief not found", 16f)
                    }
                }
                val checked = java.time.Instant.ofEpochMilli(roster.checkedAt).atZone(ClockRoster.zone)
                val stamp = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH).format(checked)
                addSchedule("Calendar checked $stamp PHT${if (roster.error != null) " · cached" else if (roster.offline) " · offline" else ""}", 14f)
            }
        }
        if (s.status) rows += Row("", 22f, mono, indicator = true)
        // Reserve hint space so touching never changes the clock size or position.
        rows += Row("Hold to exit", 22f, regular, hintRow = true)
        fun rowWidth(row: Row): Float {
            paint.typeface = row.face; paint.textSize = row.size
            val digits = paint.measureText(row.text)
            paint.textSize = row.size * 0.38f
            if (row.indicator) return 24f
            return digits + if (row.label.isNotEmpty()) paint.measureText(row.label) + row.size * 0.25f else 0f
        }
        // Long roster lines shrink independently instead of making the clock digits tiny.
        val clockWidth = rows.filter { it.digits }.maxOfOrNull { rowWidth(it) } ?: 400f
        val density = resources.displayMetrics.density
        val margin = min(16f * density, min(width, height) * 0.06f)
        val shiftRoom = min(12f * density, min(width, height) * 0.04f)
        val initialHeight = rows.sumOf { (it.size * 1.4f).toDouble() }.toFloat()
        // A tall style can leave ample horizontal room: use it for routes instead of
        // squeezing them into the narrow width of two stacked digits.
        val infoWidth = ClockLayout.infoWidth(width.toFloat(), height.toFloat(), margin + shiftRoom, clockWidth, initialHeight)
        for (index in rows.indices) {
            val row = rows[index]
            if (!row.digits && !row.indicator && rowWidth(row) > infoWidth)
                rows[index] = row.copy(size = row.size * infoWidth / rowWidth(row))
        }
        val blockWidth = rows.maxOf { rowWidth(it) }
        val blockHeight = rows.sumOf { (it.size * 1.4f).toDouble() }.toFloat()
        val scale = ClockLayout.scale(width.toFloat(), height.toFloat(), margin + shiftRoom,
            blockWidth, blockHeight, s.sizePercent)
        val actualHeight = blockHeight * scale
        val actualWidth = blockWidth * scale
        val minute = now.time / 60_000
        if (minute != shiftMinute) {
            shiftMinute = minute
            shiftX = (kotlin.random.Random.nextFloat() * 24 - 12) * density
            shiftY = (kotlin.random.Random.nextFloat() * 24 - 12) * density
        }
        val minX = margin + actualWidth / 2; val maxX = maxOf(minX, width - minX)
        val minY = margin + actualHeight / 2; val maxY = maxOf(minY, height - minY)
        fun bounce(seconds: Double, period: Double): Float {
            val phase = (seconds % period) / period
            return (1 - kotlin.math.abs(phase * 4 - 2)).toFloat()
        }
        val elapsed = SystemClock.elapsedRealtime() / 1000.0
        val baseY = if (s.reposition) floatArrayOf(0.22f, 0.5f, 0.78f)[((now.time / 3_600_000) % 3).toInt()] * height else height / 2f
        val x = (width / 2f + (if (s.pixelShift) shiftX else 0f) + if (s.drift) bounce(elapsed, 1800.0) * (maxX - minX) / 2 else 0f).coerceIn(minX, maxX)
        val y = (baseY + (if (s.pixelShift) shiftY else 0f) + if (s.drift) bounce(elapsed, 2400.0) * height * 0.1f else 0f).coerceIn(minY, maxY)
        canvas.save(); canvas.translate(x, y - actualHeight / 2); canvas.scale(scale, scale)
        var top = 0f
        rows.forEach { row ->
            val color = if (night) 0xFFFF2A1A.toInt() else row.color ?: s.color
            val total = rowWidth(row)
            var left = -total / 2
            val baseline = top + row.size
            paint.typeface = row.face; paint.shader = null; paint.color = color
            if (row.label.isNotEmpty()) {
                paint.textSize = row.size * 0.38f; paint.alpha = 140
                canvas.drawText(row.label, left, baseline, paint)
                left += paint.measureText(row.label) + row.size * 0.25f
            }
            if (row.indicator) {
                val live = mirrorState == "LIVE"
                paint.color = if (live) 0xFFFF2A1A.toInt() else if (ClockMirrorState.ready) Color.WHITE else Color.GRAY
                paint.alpha = if (live && now.time / 1000 % 2 != 0L) 0 else if (night) 90 else 180
                paint.style = if (live) Paint.Style.FILL else Paint.Style.STROKE; paint.strokeWidth = 1.8f
                canvas.drawCircle(0f, baseline - row.size * 0.35f, 6f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textSize = row.size; paint.color = color; paint.alpha = if (row.digits) 255 else 165
            if (row.hintRow && !hint) paint.alpha = 0
            if (row.digits && s.gradient && !night) paint.shader = LinearGradient(0f, top, 0f, baseline, color, s.secondColor, Shader.TileMode.CLAMP)
            canvas.drawText(row.text, left, baseline, paint)
            paint.shader = null; top += row.size * 1.4f
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (exit == null) return true
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                holding = true; hint = true; invalidate()
                removeCallbacks(hideHint); postDelayed(hideHint, 2500)
                removeCallbacks(held); postDelayed(held, 2000)
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop) cancelHold()
            }
            MotionEvent.ACTION_UP -> { cancelHold(); saveSize(); performClick() }
            MotionEvent.ACTION_CANCEL -> { cancelHold(); saveSize() }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelHold(); hint = false; invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { saveSize(); holding = false; removeCallbacks(held); removeCallbacks(hideHint); super.onDetachedFromWindow() }
}
