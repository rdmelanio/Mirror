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
    var showDepartureAlerts = true
    private var alertStop: RectF? = null
    private var alertTouch = false
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
    private var iconSizeChanged = false
    private var resizingIcon = false
    private var launchRequested = false
    private var attached = false
    private val tvChanged: () -> Unit = {
        invalidate()
        if (launchRequested && !TvLauncher.running) {
            launchRequested = false
            if (attached) android.widget.Toast.makeText(context, TvLauncher.status, android.widget.Toast.LENGTH_LONG).show()
        }
    }
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
            cancelHold(); removeCallbacks(armDrag); resizingIcon = selected == "tv_launch"
            multiplePointers = true; hint = false
            gestureSize = (if (resizingIcon) settings.tvIconSize else settings.sizePercent).toFloat()
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gestureSize = (gestureSize * detector.scaleFactor).coerceIn(if (resizingIcon) 24f else 40f, if (resizingIcon) 96f else 100f)
            val percent = gestureSize.roundToInt()
            if (resizingIcon) {
                if (percent != settings.tvIconSize) { settings = settings.copy(tvIconSize = percent); iconSizeChanged = true }
            } else if (percent != settings.sizePercent) {
                settings = settings.copy(sizePercent = percent); sizeChanged = true
            }
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { saveSize() }
    }).apply { isQuickScaleEnabled = false }
    private fun cancelExitOnly() { removeCallbacks(held) }
    private fun cancelHold() { holding = false; removeCallbacks(held) }
    internal fun stopInteraction() { cancelHold(); clearSelection(); hint = false; removeCallbacks(hideHint) }
    internal fun saveSize() {
        if (sizeChanged || layoutChanged || iconSizeChanged) {
            val fresh = ClockSettings.load(context)
            fresh.copy(sizePercent = if (sizeChanged) settings.sizePercent else fresh.sizePercent,
                positions = if (layoutChanged) settings.positions else fresh.positions,
                tvIconSize = if (iconSizeChanged) settings.tvIconSize else fresh.tvIconSize).save(context)
            sizeChanged = false; layoutChanged = false; iconSizeChanged = false
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
        hitAreas.clear(); alertStop = null
        if (showDepartureAlerts) DepartureAlerts.active(context)?.let { drawDeparture(canvas, it); return }
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
        if (s.tvIcon) drawTvIcon(canvas, zones, x, y)
        canvas.restore()
    }
    private fun drawTvIcon(canvas: Canvas, zones: ClockLayout.Zones, x: Float, y: Float) {
        val density = resources.displayMetrics.density
        val edge = zones.motionLimit + minOf(16f * density, minOf(width, height) * 0.04f)
        val size = minOf(settings.tvIconSize * density, (minOf(width, height) - 2 * edge).coerceAtLeast(0f))
        if (size <= 0f) return
        val position = settings.positions["tv_launch"] ?: ClockPosition(0.5f, 0.91f)
        val area = ClockLayout.place(width.toFloat(), height.toFloat(), edge, size, size, position)
        paint.shader = null; paint.color = Color.WHITE; paint.alpha = if (night) 100 else 210
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, size * 0.035f)
        val left = area.left + size * 0.2f; val right = area.right - size * 0.2f
        val top = area.top + size * 0.09f; val bottom = area.top + size * 0.76f
        canvas.drawRoundRect(left, top, right, bottom, size * 0.12f, size * 0.12f, paint)
        canvas.drawLine(left + size * 0.12f, top + size * 0.25f, left + size * 0.28f, top + size * 0.09f, paint)
        canvas.drawLine(left + size * 0.14f, top + size * 0.4f, left + size * 0.4f, top + size * 0.14f, paint)
        canvas.drawLine(area.centerX, bottom, area.centerX, area.bottom - size * 0.12f, paint)
        canvas.drawLine(area.centerX - size * 0.15f, area.bottom - size * 0.1f, area.centerX + size * 0.15f, area.bottom - size * 0.1f, paint)
        paint.style = Paint.Style.FILL
        recordHit(canvas, "tv_launch", area, x, y)
    }
    /** Fits only this region. Corner content cannot reduce the central clock's scale. */
    private fun drawGroup(canvas: Canvas, rows: List<Row>, area: ClockLayout.Area, alignment: Int,
                          key: String? = null, motionX: Float = 0f, motionY: Float = 0f, clock: Boolean = false, bottom: Boolean = false, respectNight: Boolean = true) {
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
            val color = if (night && respectNight) 0xFFFF2A1A.toInt() else row.color ?: settings.color
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
        val pad = if (key == "tv_launch") maxOf(5f * resources.displayMetrics.density, (48f * resources.displayMetrics.density - area.width) / 2) else 5f * resources.displayMetrics.density
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
    private fun drawDeparture(canvas: Canvas, alert: DepartureAlerts.Active) {
        val now = System.currentTimeMillis()
        val warning = alert.kind == DeparturePlan.Kind.WARNING
        val lit = !warning || (now - alert.started) / 1000 % 2 == 0L
        val color = if (warning) 0xFFFF2A1A.toInt() else 0xFFFFB000.toInt()
        val green = 0xFF00E040.toInt(); val cyan = 0xFF00E5FF.toInt()
        val density = resources.displayMetrics.density
        val z = EcamLayout.regions(width.toFloat(), height.toFloat(), density)
        val movement = minOf(4f*density, z.inset/3)
        val dx = (((now/60_000)%5).toInt()-2)*movement
        val dy = (((now/60_000+2)%5).toInt()-2)*movement
        canvas.save(); canvas.translate(dx, dy)
        paint.shader = null; paint.alpha = 255; paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        if (lit) canvas.drawRect(z.header.left, z.header.top, z.header.right, z.header.bottom, paint)
        paint.style = Paint.Style.FILL
        if (lit) drawGroup(canvas, listOf(Row(if (alert.test) "TEST / MASTER ${alert.kind.name}" else "MASTER ${alert.kind.name}", 46f, bold, color = color)),
            z.header.copy(left = z.header.left+z.gap, top = z.header.top+z.gap/2), -1, respectNight = false)
        paint.color = Color.GRAY; paint.alpha = 140
        canvas.drawLine(z.duty.left, z.duty.bottom+z.gap/2, z.duty.right, z.duty.bottom+z.gap/2, paint)
        drawGroup(canvas, listOf(
            Row("DUTY / DEPARTURE", 22f, regular, color = Color.WHITE),
            Row(alert.duty, 32f, mono, color = green),
            Row("REPORT  ${DepartureAlerts.stamp(alert.reporting)}", 24f, regular, color = Color.WHITE)), z.duty, -1, respectNight = false)
        val remaining = ((alert.warningAt-now).coerceAtLeast(0)+59_999)/60_000
        val finalTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm 'PHT'", Locale.ENGLISH)
            .format(java.time.Instant.ofEpochMilli(alert.warningAt).atZone(ClockRoster.zone))
        val actionRows = if (warning) listOf(
            Row("DEPARTURE", 22f, regular, color = Color.WHITE),
            Row("LEAVE FOR DUTY", 32f, bold, color = cyan),
            Row(". STOP TO ACKNOWLEDGE", 22f, regular, color = cyan)) else listOf(
            Row("PREPARATION", 22f, regular, color = Color.WHITE),
            Row("PREPARE TO LEAVE", 28f, bold, color = cyan),
            Row("$remaining MIN TO ${if (settings.warningEnabled) "FINAL ALERT" else "REPORT"}", 24f, regular, color = color),
            Row("AT $finalTime", 22f, regular, color = cyan))
        drawGroup(canvas, actionRows, z.action, -1, respectNight = false)
        val button = RectF(z.stop.left, z.stop.top, z.stop.right, z.stop.bottom)
        paint.shader = null; paint.alpha = 255; paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*density
        canvas.drawRect(button, paint); paint.style = Paint.Style.FILL
        paint.typeface = bold; paint.textSize = minOf(28*density, button.height()*.42f)
        val text = if (warning) "STOP / ACKNOWLEDGE" else "ACKNOWLEDGE"
        paint.textSize = minOf(paint.textSize, paint.textSize*(button.width()-2*z.gap)/paint.measureText(text))
        canvas.drawText(text, button.centerX()-paint.measureText(text)/2, button.centerY()-(paint.ascent()+paint.descent())/2, paint)
        drawGroup(canvas, listOf(Row(if (warning) "AUTO CLEAR / 10 MIN" else "AMBER / ACKNOWLEDGE WHEN READY", 14f, regular, color = Color.WHITE)),
            z.footer, -1, respectNight = false)
        alertStop = RectF(button.left+dx, button.top+dy, button.right+dx, button.bottom+dy)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (exit == null) return true
        if (showDepartureAlerts && DepartureAlerts.active(context) != null) {
            stopInteraction()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; alertTouch = alertStop?.contains(event.x, event.y) == true }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop) alertTouch = false
                MotionEvent.ACTION_UP -> {
                    if (alertTouch && alertStop?.contains(event.x, event.y) == true) { DepartureAlerts.acknowledge(context); invalidate(); performClick() }
                    alertTouch = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> alertTouch = false
            }
            return true
        }
        alertTouch = false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; multiplePointers = false; dragging = false
                downItem = hitAreas.entries.lastOrNull { (_, a) ->
                    event.x in a.left..a.right && event.y in a.top..a.bottom
                }?.key?.takeIf { settings.layoutEditing || it == "tv_launch" }
                holding = true; hint = true; invalidate()
                removeCallbacks(hideHint); postDelayed(hideHint, 2500)
                removeCallbacks(held); postDelayed(held, 2000)
                removeCallbacks(armDrag)
                if (downItem != null && settings.layoutEditing && (downItem == selected || downItem == "tv_launch")) {
                    if (downItem == "tv_launch") selected = downItem
                    removeCallbacks(clearSelectionTask); postDelayed(armDrag, 450)
                }
                if (downItem == "tv_launch") cancelExitOnly()
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
                if (tap && downItem == "tv_launch") {
                    clearSelection()
                    if (!TvLauncher.running) { launchRequested = true; TvLauncher.launch(context) }
                } else if (tap) selected = downItem
                dragging = false; saveSize(); if (selected != null) finishSelection(); invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { cancelHold(); clearSelection(); saveSize(); invalidate() }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelHold(); removeCallbacks(armDrag); if (selected != "tv_launch") clearSelection(); multiplePointers = true; hint = false; invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; TvLauncher.listeners.add(tvChanged) }
    override fun onDetachedFromWindow() { attached = false; launchRequested = false; TvLauncher.listeners.remove(tvChanged); saveSize(); stopInteraction(); super.onDetachedFromWindow() }
}
