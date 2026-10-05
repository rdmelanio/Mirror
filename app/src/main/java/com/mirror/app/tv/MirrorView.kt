package com.mirror.app.tv

import android.content.Context
import android.graphics.Matrix
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

fun innerArea(width: Int, height: Int, settings: TvSettings): RectF {
    val border = if (settings.mode == 1) min(width, height) * floatArrayOf(.15f, .25f, .35f)[settings.size] else 0f
    return RectF(border, border, width - border, height - border)
}

class MirrorView(context: Context, private val settings: TvSettings) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var frame: VideoFrame? = null
    private val lock = Any()
    private var pending: VideoFrame? = null
    private var scheduled = false
    private var accepting = false
    private var frozen = false
    var opticalZoom = false
    fun setFrozen(value: Boolean): Boolean {
        synchronized(lock) {
            if (value && frame == null) return false
            frozen = value; pending?.release(); pending = null; scheduled = false
        }
        return true
    }
    fun snapshot(): VideoFrame? {
        val value = frame ?: return null
        val copy = value.bitmap.copy(android.graphics.Bitmap.Config.RGB_565, false) ?: return null
        return VideoFrame(copy, (value.rotation + settings.rotation) % 360)
    }
    private val matrix = Matrix()
    private var area = RectF()
    private val oval = Path()
    private var transformDirty = true
    val drawnFrames = AtomicInteger()
    fun startFrames() { synchronized(lock) { accepting = true } }
    fun ready(): Boolean = synchronized(lock) { accepting && !frozen && !scheduled }
    fun submit(value: VideoFrame) {
        synchronized(lock) {
            if (!accepting || frozen) { value.release(); return }
            pending?.release(); pending = value
            scheduled = true
        }
        postInvalidateOnAnimation()
    }
    fun stopFrames() {
        synchronized(lock) { accepting = false; frozen = false; pending?.release(); pending = null; scheduled = false }
        // An outstanding hardware frame may still reference this bitmap; leave it to GC.
        frame = null; transformDirty = true; invalidate()
    }
    fun refresh() {
        transformDirty = true
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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { transformDirty = true }
    private fun rebuildTransform(value: VideoFrame) {
        val bitmap = value.bitmap
        area = innerArea(width, height, settings)
        oval.reset(); oval.addOval(area, Path.Direction.CW)
        val rotation = (value.rotation + settings.rotation) % 360
        val sideways = rotation % 180 != 0
        val rotatedWidth = if (sideways) bitmap.height else bitmap.width
        val rotatedHeight = if (sideways) bitmap.width else bitmap.height
        val x = area.width() / rotatedWidth; val y = area.height() / rotatedHeight
        val base = if (settings.mode == 1 || settings.fill) max(x, y) else min(x, y)
        // Real zoom is already in the phone pixels. A small extra crop supplies pan travel.
        val scale = base * if (settings.mode != 0) 1f else if (!opticalZoom) settings.zoom
            else if (settings.zoom > 1f && settings.pan != 0f) 1.15f else 1f
        val travel = max(0f, (rotatedWidth * scale - area.width()) / 2f)
        matrix.reset(); matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postRotate(rotation.toFloat()); matrix.postScale(scale, scale)
        if (settings.flip) matrix.postScale(-1f, 1f)
        matrix.postTranslate(area.centerX() - settings.pan * travel, area.centerY())
        transformDirty = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK)
        val next = synchronized(lock) { pending.also { pending = null } }
        val previous = frame
        if (next != null) {
            if (previous == null || previous.bitmap.width != next.bitmap.width ||
                previous.bitmap.height != next.bitmap.height || previous.rotation != next.rotation) transformDirty = true
            frame = next
        }
        val value = frame
        if (value != null) {
            if (transformDirty) rebuildTransform(value)
            canvas.save(); canvas.clipRect(area)
            if (settings.mode == 1 && settings.shape == 1) canvas.clipPath(oval)
            canvas.drawBitmap(value.bitmap, matrix, paint)
            canvas.restore()
        }
        if (next != null) {
            drawnFrames.incrementAndGet()
            if (previous != null) {
                if (!canvas.isHardwareAccelerated) previous.release()
                else if (Build.VERSION.SDK_INT >= 29) {
                    // Commit means the render thread has consumed the previous display list.
                    // Never offer an inBitmap candidate while it is still being rendered.
                    viewTreeObserver.registerFrameCommitCallback { previous.release() }
                }
            }
            synchronized(lock) { scheduled = false }
        }
    }
}

