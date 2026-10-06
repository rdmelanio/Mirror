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
        set(value) { field = value; if (!value.layoutEditing) clearSelection(); invalidate() }
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
    private var layoutChanged = false
    private val hitAreas = linkedMapOf<String, ClockLayout.Area>()
    private var selected: String? = null
    private var downItem: String? = null
    private var dragging = false
    private var multiplePointers = false
    private var dragArea: ClockLayout.Area? = null
    private var motionX = 0f; private var motionY = 0f
    private val clearSelectionTask = Runnable { clearSelection(); invalidate() }
    private val armDrag = Runnable {
        if (holding && downItem != null && downItem == selected && settings.layoutEditing) {
            dragArea = hitAreas[downItem]; dragging = true; cancelHold(); invalidate()
        }
    }
    private fun clearSelection() { selected = null; dragging = false; removeCallbacks(armDrag); removeCallbacks(clearSelectionTask) }
    private fun finishSelection() { removeCallbacks(clearSelectionTask); postDelayed(clearSelectionTask, 8000) }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelHold(); clearSelection(); multiplePointers = true; hint = false; gestureSize = settings.sizePercent.toFloat()
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
    internal fun stopInteraction() { cancelHold(); clearSelection(); hint = false; removeCallbacks(hideHint) }
    internal fun saveSize() {
        if (sizeChanged || layoutChanged) {
            val fresh = ClockSettings.load(context)
            fresh.copy(sizePercent = if (sizeChanged) settings.sizePercent else fresh.sizePercent,
                positions = if (layoutChanged) settings.positions else fresh.positions).save(context)
            sizeChanged = false; layoutChanged = false
        }
    }
    private val hideHint = Runnable { hint = false; invalidate() }
    private val held = Runnable { if (holding) { holding = false; exit?.invoke() } }
    init { setBackgroundColor(Color.BLACK); isClickable = true; contentDescription = "Mirror clock. Pinch to resize. Tap an item, then hold and drag to move it. Hold empty space for two seconds to exit." }
    private data class Row(val text: String, val size: Float, val face: Typeface, val label: String = "", val digits: Boolean = false, val color: Int? = null)
    private fun format(pattern: String, date: Date, utc: Boolean = false): String = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
        timeZone = if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }.format(date)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitAreas.clear()
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
        val clockRows = rows.toList()
        val dateRows = mutableListOf<Row>()
        val alarmRows = mutableListOf<Row>()
        val weatherRows = mutableListOf<Row>()
        val todayRows = mutableListOf<Row>()
        val tomorrowRows = mutableListOf<Row>()
        val footerRows = mutableListOf<Row>()
        if (s.date) dateRows += Row(format("EEE dd MMM", now, s.usesUtc).uppercase(Locale.ENGLISH), 23f, regular, color = s.dateColor)
        if (s.alarm) context.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
            alarmRows += Row(format(if (use24) "HH:mm" else "h:mm a", Date(it.triggerTime)), 20f, regular, "ALARM", color = s.alarmColor)
        }
        if (s.weather && s.city.isNotBlank()) weatherRows += Row(ClockWeather.display(context, s), 23f, regular, color = s.weatherColor)
        if (s.schedule) {
            val titleFormat = java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH)
            fun addSchedule(target: MutableList<Row>, text: String, size: Float = 22f) {
                target += Row(text, size, regular, color = s.scheduleColor)
            }
            if (!ClockCalendar.hasPermission(context)) {
                addSchedule(todayRows, "Calendar access needed", 19f)
                addSchedule(todayRows, "Enable in Clock settings", 17f)
            } else if (roster.calendarId != s.calendarId || roster.loading) addSchedule(todayRows, "Loading calendar…")
            else if (roster.checkedAt == 0L) {
                addSchedule(todayRows, "Calendar unavailable", 19f)
                addSchedule(todayRows, "Choose calendar in settings", 17f)
            } else {
                val today = java.time.Instant.ofEpochMilli(now.time).atZone(ClockRoster.zone).toLocalDate()
                ClockRoster.days(roster.duties, now.time).forEachIndexed { index, day ->
                    val target = if (index == 0) todayRows else tomorrowRows
                    val label = day.label.replace("NEXT DUTY · TOMORROW", "NEXT · TOMORROW")
                    addSchedule(target, "$label · ${titleFormat.format(day.date).uppercase(Locale.ENGLISH)}", 18f)
                    val dayStart = day.date.atStartOfDay(ClockRoster.zone).toInstant().toEpochMilli()
                    if (dayStart !in roster.windowStart until roster.windowEnd) addSchedule(target, "Calendar needs refreshing", 19f)
                    else if (day.duties.isEmpty()) {
                        val emptyLabel = if (day.date == today.plusDays(1)) "Tomorrow" else if (day.date == today) "Today" else titleFormat.format(day.date)
                        addSchedule(target, "$emptyLabel:", 19f)
                        addSchedule(target, "no calendar entry", 21f)
                    } else day.duties.forEach { duty ->
                        // Keep the complete route on one line; its duty times get their own line.
                        addSchedule(target, duty.text, 23f)
                        addSchedule(target, ClockRoster.range(duty, use24), 21f)
                        if (duty.day < today) addSchedule(target, "From ${titleFormat.format(duty.day)}", 15f)
                        if (duty.calendarTimes) addSchedule(target, "Calendar times (report/debrief missing)", 14f)
                    }
                }
                val checked = java.time.Instant.ofEpochMilli(roster.checkedAt).atZone(ClockRoster.zone)
                val stamp = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH).format(checked)
                footerRows += Row("Calendar checked $stamp PHT${if (roster.error != null) " · cached" else if (roster.offline) " · offline" else ""}", 12f, regular, color = s.scheduleColor)
            }
        }
        val density = resources.displayMetrics.density
        val zones = ClockLayout.zones(width.toFloat(), height.toFloat(), density)
        val minute = now.time / 60_000
        if (minute != shiftMinute) {
            shiftMinute = minute
            shiftX = kotlin.random.Random.nextFloat() * 24 - 12
            shiftY = kotlin.random.Random.nextFloat() * 24 - 12
        }
        fun bounce(seconds: Double, period: Double): Float {
            val phase = (seconds % period) / period
            return (1 - kotlin.math.abs(phase * 4 - 2)).toFloat()
        }
        val elapsed = SystemClock.elapsedRealtime() / 1000.0
        val hourly = if (s.reposition) floatArrayOf(-8f, 0f, 8f)[((now.time / 3_600_000) % 3).toInt()] else 0f
        val x = ((if (s.pixelShift) shiftX else 0f) * density +
            if (s.drift) bounce(elapsed, 1800.0) * 6f * density else 0f).coerceIn(-zones.motionLimit, zones.motionLimit)
        val y = (((if (s.pixelShift) shiftY else 0f) + hourly) * density +
            if (s.drift) bounce(elapsed, 2400.0) * 6f * density else 0f).coerceIn(-zones.motionLimit, zones.motionLimit)
        // Every zone shares the same bounded burn-in transform, including corner information.
        motionX = x; motionY = y
        canvas.save(); canvas.translate(x, y)
        drawGroup(canvas, clockRows, zones.clock, 0, "clock", x, y, clock = true)
        drawGroup(canvas, dateRows, if (alarmRows.isEmpty()) zones.date else zones.date.copy(bottom = zones.date.top + zones.date.height * 0.55f), -1, "date", x, y)
        drawGroup(canvas, alarmRows, if (dateRows.isEmpty()) zones.date else zones.date.copy(top = zones.date.top + zones.date.height * 0.55f), -1, "alarm", x, y)
        drawGroup(canvas, weatherRows, zones.weather, 1, "weather", x, y)
        drawGroup(canvas, todayRows, zones.today, -1, "today", x, y, bottom = true)
        drawGroup(canvas, tomorrowRows, zones.tomorrow, 1, "tomorrow", x, y, bottom = true)
        val footer = zones.footer
        val footerText = footer.copy(right = footer.left + footer.width * 0.64f)
        drawGroup(canvas, footerRows, footerText, -1, "calendar_checked", x, y, bottom = true)
        val hintArea = footer.copy(left = footer.left + footer.width * 0.66f, right = footer.right - 20f * density)
        if (hint || selected != null) drawGroup(canvas, listOf(Row(if (selected != null) "Hold + drag to move" else "Hold to exit", 14f, regular)), hintArea, 1, bottom = true)
        if (s.status) {
            val radius = minOf(5f * density, footer.height * 0.25f)
            val location = s.positions["status"]
            val limit = zones.motionLimit + minOf(16f * density, minOf(width, height) * 0.04f)
            val cx = location?.let { (it.x * width).coerceIn(limit + radius, maxOf(limit + radius, width - limit - radius)) } ?: (footer.right - 6f * density)
            val cy = location?.let { (it.y * height).coerceIn(limit + radius, maxOf(limit + radius, height - limit - radius)) } ?: footer.centerY
            drawIndicator(canvas, cx, cy, radius, now.time)
            recordHit(canvas, "status", ClockLayout.Area(cx - radius, cy - radius, cx + radius, cy + radius), x, y)
        }
        canvas.restore()
    }
    /** Fits only this region. Corner content cannot reduce the central clock's scale. */
    private fun drawGroup(canvas: Canvas, rows: List<Row>, area: ClockLayout.Area, alignment: Int,
                          key: String? = null, motionX: Float = 0f, motionY: Float = 0f, clock: Boolean = false, bottom: Boolean = false) {
        if (rows.isEmpty() || area.width <= 0f || area.height <= 0f) return
        fun rowWidth(row: Row): Float {
            paint.typeface = row.face; paint.textSize = row.size
            val text = paint.measureText(row.text)
            paint.textSize = row.size * 0.38f
            return text + if (row.label.isNotEmpty()) paint.measureText(row.label) + row.size * 0.25f else 0f
        }
        val leading = if (clock) 1.18f else 1.32f
        val blockWidth = rows.maxOf { rowWidth(it) }
        val blockHeight = rows.sumOf { (it.size * leading).toDouble() }.toFloat()
        var scale = ClockLayout.scale(area.width, area.height, 0f, blockWidth, blockHeight,
            if (clock) settings.sizePercent else 100)
        if (!clock) scale = minOf(scale, resources.displayMetrics.density)
        if (scale <= 0f) return
        val actualHeight = blockHeight * scale
        var top = if (clock) area.centerY - actualHeight / 2 else if (bottom) area.bottom - actualHeight else area.top
        var anchor = if (alignment < 0) area.left else if (alignment > 0) area.right else area.centerX
        val actualWidth = blockWidth * scale
        key?.let { settings.positions[it] }?.let { position ->
            val safe = ClockLayout.zones(width.toFloat(), height.toFloat(), resources.displayMetrics.density)
            val edge = minOf(16f * resources.displayMetrics.density, minOf(width, height) * 0.04f) + safe.motionLimit
            val placed = ClockLayout.place(width.toFloat(), height.toFloat(), edge, actualWidth, actualHeight, position)
            top = placed.top
            anchor = if (alignment < 0) placed.left else if (alignment > 0) placed.right else placed.centerX
        }
        val actualLeft = if (alignment < 0) anchor else if (alignment > 0) anchor - actualWidth else anchor - actualWidth / 2
        canvas.save(); canvas.translate(anchor, top); canvas.scale(scale, scale)
        var rowTop = 0f
        rows.forEach { row ->
            val width = rowWidth(row)
            var left = if (alignment < 0) 0f else if (alignment > 0) -width else -width / 2
            val baseline = rowTop + row.size * 0.9f
            val color = if (night) 0xFFFF2A1A.toInt() else row.color ?: settings.color
            paint.typeface = row.face; paint.shader = null; paint.color = color
            if (row.label.isNotEmpty()) {
                paint.textSize = row.size * 0.38f; paint.alpha = 140
                canvas.drawText(row.label, left, baseline, paint)
                left += paint.measureText(row.label) + row.size * 0.25f
            }
            paint.textSize = row.size; paint.color = color; paint.alpha = if (row.digits) 255 else 180
            if (row.digits && settings.gradient && !night)
                paint.shader = LinearGradient(0f, rowTop, 0f, baseline, color, settings.secondColor, Shader.TileMode.CLAMP)
            canvas.drawText(row.text, left, baseline, paint)
            paint.shader = null; rowTop += row.size * leading
        }
        canvas.restore()
        if (key != null) recordHit(canvas, key, ClockLayout.Area(actualLeft, top, actualLeft + actualWidth, top + actualHeight), motionX, motionY)
    }
    private fun recordHit(canvas: Canvas, key: String, area: ClockLayout.Area, motionX: Float, motionY: Float) {
        val pad = 5f * resources.displayMetrics.density
        val hit = ClockLayout.Area(area.left + motionX - pad, area.top + motionY - pad, area.right + motionX + pad, area.bottom + motionY + pad)
        hitAreas[key] = hit
        if (selected == key) {
            paint.shader = null; paint.color = if (night) 0xFFFF2A1A.toInt() else settings.scheduleColor
            paint.alpha = 130; paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
            canvas.drawRoundRect(area.left - pad, area.top - pad, area.right + pad, area.bottom + pad, pad, pad, paint)
            paint.style = Paint.Style.FILL
        }
    }
    private fun drawIndicator(canvas: Canvas, x: Float, y: Float, radius: Float, now: Long) {
        val live = mirrorState == "LIVE"
        paint.shader = null
        paint.color = if (live) 0xFFFF2A1A.toInt() else if (ClockMirrorState.ready) Color.WHITE else Color.GRAY
        paint.alpha = if (live && now / 1000 % 2 != 0L) 0 else if (night) 90 else 180
        paint.style = if (live) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius * 0.25f)
        canvas.drawCircle(x, y, radius, paint); paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (exit == null) return true
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; multiplePointers = false; dragging = false
                downItem = if (settings.layoutEditing) hitAreas.entries.lastOrNull { (_, a) ->
                    event.x in a.left..a.right && event.y in a.top..a.bottom
                }?.key else null
                holding = true; hint = true; invalidate()
                removeCallbacks(hideHint); postDelayed(hideHint, 2500)
                removeCallbacks(held); postDelayed(held, 2000)
                removeCallbacks(armDrag)
                if (downItem != null && downItem == selected) { removeCallbacks(clearSelectionTask); postDelayed(armDrag, 450) }
                if (downItem == null) clearSelection()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !multiplePointers) {
                    val key = downItem; val original = dragArea
                    if (key != null && original != null) {
                        val centerX = original.centerX + event.x - downX - motionX
                        val centerY = original.centerY + event.y - downY - motionY
                        settings = settings.copy(positions = settings.positions + (key to ClockPosition(
                            (centerX / width).coerceIn(0f, 1f), (centerY / height).coerceIn(0f, 1f))))
                        layoutChanged = true
                    }
                } else if (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop) {
                    cancelHold(); removeCallbacks(armDrag)
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = !multiplePointers && !dragging && kotlin.math.abs(event.x - downX) <= touchSlop && kotlin.math.abs(event.y - downY) <= touchSlop
                cancelHold(); removeCallbacks(armDrag)
                if (tap) selected = downItem
                dragging = false; saveSize(); if (selected != null) finishSelection(); invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { cancelHold(); clearSelection(); saveSize(); invalidate() }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelHold(); clearSelection(); multiplePointers = true; hint = false; invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { saveSize(); stopInteraction(); super.onDetachedFromWindow() }
}
