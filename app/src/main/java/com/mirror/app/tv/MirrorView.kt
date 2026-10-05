package com.mirror.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.mirror.app.core.TvSettings
import kotlin.math.max
import kotlin.math.min

fun innerArea(width: Int, height: Int, settings: TvSettings): RectF {
    val border = if (settings.mode == 1) min(width, height) * floatArrayOf(.15f, .25f, .35f)[settings.size] else 0f
    return RectF(border, border, width - border, height - border)
}

class MirrorView(context: Context, private val settings: TvSettings) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    // A single pending frame bounds memory when decoding outpaces display rendering.
    private val lock = Any()
    private var pending: Bitmap? = null
    private var scheduled = false
    private var accepting = false
    private val present = Runnable {
        synchronized(lock) {
            val next = pending; pending = null; scheduled = false
            if (next != null) { bitmap = next; invalidate() }
        }
    }
    fun startFrames() { synchronized(lock) { accepting = true } }
    fun submit(value: Bitmap) {
        synchronized(lock) {
            if (!accepting) { value.recycle(); return }
            pending?.recycle(); pending = value
            if (!scheduled) { scheduled = true; postOnAnimation(present) }
        }
    }
    fun stopFrames() {
        synchronized(lock) {
            accepting = false; removeCallbacks(present); pending?.recycle(); pending = null; scheduled = false
        }
        // Displayed bitmaps may still be referenced by the hardware render thread;
        // allow the runtime to reclaim them instead of recycling them immediately.
        bitmap = null; invalidate()
    }
    fun refresh() {
        val saturation = ColorMatrix().apply { setSaturation(1f + settings.saturation / 50f) }
        val contrast = 1f + settings.contrast / 100f
        val offset = settings.brightness * 2.55f + 128f * (1f - contrast)
        val warmth = settings.warmth / 250f
        val adjustments = ColorMatrix(floatArrayOf(
            contrast * (1 + warmth), 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast * (1 - warmth), 0f, offset,
            0f, 0f, 0f, 1f, 0f))
        adjustments.postConcat(saturation)
        paint.colorFilter = ColorMatrixColorFilter(adjustments)
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = when (settings.softFocus) { 1 -> 1.5f; 2 -> 3f; else -> 0f }
            setRenderEffect(if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK)
        val frame = bitmap ?: return
        val area = innerArea(width, height, settings)
        canvas.save(); canvas.clipRect(area)
        if (settings.mode == 1 && settings.shape == 1) canvas.clipPath(Path().apply { addOval(area, Path.Direction.CW) })
        val sideways = settings.rotation % 180 != 0
        val rotatedWidth = if (sideways) frame.height else frame.width
        val rotatedHeight = if (sideways) frame.width else frame.height
        val x = area.width() / rotatedWidth; val y = area.height() / rotatedHeight
        val base = if (settings.mode == 1 || !settings.fill) min(x, y) else max(x, y)
        val zoom = if (settings.mode == 0) settings.zoom else 1f
        val scale = base * zoom
        val travel = max(0f, (rotatedWidth * scale - area.width()) / 2f)
        canvas.translate(area.centerX() - settings.pan * travel, area.centerY())
        if (settings.flip) canvas.scale(-1f, 1f)
        canvas.rotate(settings.rotation.toFloat()); canvas.scale(scale, scale)
        canvas.drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, paint)
        canvas.restore()
    }
}
