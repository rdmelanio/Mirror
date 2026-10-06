package com.mirror.app.phone

import android.app.AlarmManager
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
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
    private val regular = resources.getFont(R.font.b612_regular)
    private val bold = resources.getFont(R.font.b612_bold)
    private val mono = resources.getFont(R.font.b612_mono)
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shiftMinute = Long.MIN_VALUE
    private var shiftX = 0f; private var shiftY = 0f
    private var hint = false
    private var holding = false
    private val hideHint = Runnable { hint = false; invalidate() }
    private val held = Runnable { if (holding) { holding = false; exit?.invoke() } }
    init { setBackgroundColor(Color.BLACK); isClickable = true; contentDescription = "Mirror clock. Hold anywhere for two seconds to exit." }
    private data class Row(val text: String, val size: Float, val face: Typeface, val label: String = "", val digits: Boolean = false, val live: Boolean = false)
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
        val primary = if (s.primaryUtc) utc else local
        val rows = mutableListOf<Row>()
        when (s.style) {
            "Cockpit" -> {
                rows += Row(primary, 100f, mono, if (s.primaryUtc) "UTC" else "LOC", true)
                rows += Row(if (s.primaryUtc) local else utc, 54f, mono, if (s.primaryUtc) "LOC" else "UTC", true)
            }
            "Stacked" -> {
                rows += Row(format(if (s.primaryUtc || use24) "HH" else "hh", now, s.primaryUtc), 158f, bold, digits = true)
                rows += Row(format("mm", now, s.primaryUtc), 158f, bold, digits = true)
                if (s.seconds) rows += Row(format("ss", now, s.primaryUtc), 38f, regular, "SEC", true)
                if (s.primaryUtc || !use24) rows += Row(if (s.primaryUtc) "UTC" else format("a", now), 22f, regular)
            }
            "Word clock" -> {
                val calendar = Calendar.getInstance(if (s.primaryUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
                val words = ClockWords.time(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)).split(" ")
                var line = ""
                words.forEach { word ->
                    if ((line + " " + word).length > 17) { rows += Row(line, 48f, regular, digits = true); line = word }
                    else line = if (line.isEmpty()) word else "$line $word"
                }
                rows += Row(line, 48f, regular, digits = true)
                if (s.seconds) rows += Row(format("ss", now), 28f, regular, "SEC", true)
                if (s.primaryUtc) rows += Row("UTC", 22f, regular)
            }
            else -> rows += Row(primary, 112f, if (s.minimalFont == "B612") regular else thin, digits = true)
        }
        if (s.date) rows += Row(format("EEE dd MMM", now, s.primaryUtc).uppercase(Locale.ENGLISH), 25f, regular)
        if (s.alarm) context.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
            rows += Row(format(if (use24) "HH:mm" else "hh:mm a", Date(it.triggerTime)), 24f, regular, "ALARM")
        }
        if (s.weather && s.city.isNotBlank()) rows += Row(ClockWeather.display(context, s), 24f, regular)
        if (s.status) rows += Row(mirrorState, 22f, mono, live = mirrorState == "LIVE")
        if (hint) rows += Row("Hold to exit", 22f, regular)
        fun rowWidth(row: Row): Float {
            paint.typeface = row.face; paint.textSize = row.size
            val digits = paint.measureText(row.text)
            paint.textSize = row.size * 0.38f
            return digits + if (row.label.isNotEmpty()) paint.measureText(row.label) + row.size * 0.25f else if (row.live) 26f else 0f
        }
        val blockWidth = rows.maxOf { rowWidth(it) }
        val blockHeight = rows.sumOf { (it.size * 1.4f).toDouble() }.toFloat()
        val density = resources.displayMetrics.density
        val margin = min(32f * density, min(width, height) * 0.1f)
        val scale = min((width - margin * 2) / blockWidth, (height - margin * 2) * 0.72f / blockHeight).coerceAtMost(2.5f * density)
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
        val color = if (night) 0xFFFF2A1A.toInt() else s.color
        var top = 0f
        rows.forEach { row ->
            val total = rowWidth(row)
            var left = -total / 2
            val baseline = top + row.size
            paint.typeface = row.face; paint.shader = null; paint.color = color
            if (row.label.isNotEmpty()) {
                paint.textSize = row.size * 0.38f; paint.alpha = 140
                canvas.drawText(row.label, left, baseline, paint)
                left += paint.measureText(row.label) + row.size * 0.25f
            }
            if (row.live) {
                paint.color = 0xFFFF2A1A.toInt(); paint.alpha = 180
                canvas.drawCircle(left + 6, baseline - row.size * 0.35f, 5f, paint); left += 26
            }
            paint.textSize = row.size; paint.color = color; paint.alpha = if (row.digits) 255 else 165
            if (row.digits && s.gradient && !night) paint.shader = LinearGradient(0f, top, 0f, baseline, color, s.secondColor, Shader.TileMode.CLAMP)
            canvas.drawText(row.text, left, baseline, paint)
            paint.shader = null; top += row.size * 1.4f
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (exit == null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true; hint = true; invalidate()
                removeCallbacks(hideHint); postDelayed(hideHint, 2500)
                removeCallbacks(held); postDelayed(held, 2000)
            }
            MotionEvent.ACTION_UP -> { holding = false; removeCallbacks(held); performClick() }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { holding = false; removeCallbacks(held) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { holding = false; removeCallbacks(held); removeCallbacks(hideHint); super.onDetachedFromWindow() }
}
