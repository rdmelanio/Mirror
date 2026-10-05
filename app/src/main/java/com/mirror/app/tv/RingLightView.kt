package com.mirror.app.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.mirror.app.core.TvSettings
import kotlin.math.min

class RingLightView(context: Context, private val settings: TvSettings) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        if (settings.mode != 1) return
        val base = intArrayOf(Color.rgb(255, 215, 168), Color.rgb(255, 241, 222), Color.rgb(242, 246, 255))[settings.temperature]
        val level = settings.light / 100f
        val color = Color.rgb((Color.red(base) * level).toInt(), (Color.green(base) * level).toInt(), (Color.blue(base) * level).toInt())
        val area = innerArea(width, height, settings)
        paint.shader = null; paint.color = color
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (settings.shape == 1) addOval(area, Path.Direction.CW) else addRect(area, Path.Direction.CW)
        }
        canvas.drawPath(outside, paint)
        if (settings.shape == 1) {
            // Several translucent oval strokes blend the ring into the camera area.
            paint.style = Paint.Style.STROKE
            val glow = min(width, height) * .035f
            for (step in 12 downTo 1) {
                paint.strokeWidth = glow * step / 12
                paint.color = color; paint.alpha = 14
                canvas.drawOval(area, paint)
            }
            paint.style = Paint.Style.FILL; paint.alpha = 255
        } else if (settings.shape == 2) {
            val fade = min(width, height) * .05f
            val transparent = color and 0x00ffffff
            fun edge(rect: RectF, x1: Float, y1: Float, x2: Float, y2: Float) {
                paint.shader = LinearGradient(x1, y1, x2, y2, color, transparent, Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
            }
            edge(RectF(area.left, area.top, area.left + fade, area.bottom), area.left, 0f, area.left + fade, 0f)
            edge(RectF(area.right - fade, area.top, area.right, area.bottom), area.right, 0f, area.right - fade, 0f)
            edge(RectF(area.left, area.top, area.right, area.top + fade), 0f, area.top, 0f, area.top + fade)
            edge(RectF(area.left, area.bottom - fade, area.right, area.bottom), 0f, area.bottom, 0f, area.bottom - fade)
            paint.shader = null
        }
    }
}
