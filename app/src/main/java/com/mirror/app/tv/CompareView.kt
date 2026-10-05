package com.mirror.app.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.mirror.app.core.dp
import kotlin.math.min

/** Owns no decoded/pool bitmaps; the activity holds at most three immutable copies. */
class CompareView(context: Context, private val snapshots: List<VideoFrame>,
                  private val flip: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var selected = 0
    private var full = false
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = "Outfit Compare. Left or Right selects a snapshot. OK shows it full screen. Back returns to live mirror." }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val count = if (full) 1 else snapshots.size
        repeat(count) { slot ->
            val index = if (full) selected else slot
            val area = RectF(slot * width.toFloat() / count + context.dp(6), context.dp(44).toFloat(),
                (slot + 1) * width.toFloat() / count - context.dp(6), height - context.dp(76).toFloat())
            val frame = snapshots[index]
            val bitmap = frame.bitmap
            val sideways = frame.rotation % 180 != 0
            val scale = min(area.width() / (if (sideways) bitmap.height else bitmap.width),
                area.height() / (if (sideways) bitmap.width else bitmap.height))
            matrix.reset(); matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
            matrix.postRotate(frame.rotation.toFloat()); matrix.postScale(if (flip) -scale else scale, scale)
            matrix.postTranslate(area.centerX(), area.centerY())
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawBitmap(bitmap, matrix, paint)
            paint.textSize = context.dp(22).toFloat()
            canvas.drawText("${index + 1}", area.left + context.dp(12), context.dp(30).toFloat(), paint)
            if (!full && index == selected) {
                paint.color = Color.rgb(255, 227, 188); paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(3).toFloat()
                canvas.drawRect(area, paint); paint.style = Paint.Style.FILL
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> selected = (selected - 1 + snapshots.size) % snapshots.size
            KeyEvent.KEYCODE_DPAD_RIGHT -> selected = (selected + 1) % snapshots.size
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> full = true
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate(); return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (!full) selected = (event.x * snapshots.size / width).toInt().coerceIn(0, snapshots.lastIndex)
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); full = true; invalidate(); return true }
}
