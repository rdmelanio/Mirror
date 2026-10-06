package com.mirror.app.phone

/** Independent display zones: info content never participates in sizing the central clock. */
object ClockLayout {
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = (right - left).coerceAtLeast(0f)
        val height get() = (bottom - top).coerceAtLeast(0f)
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }
    data class Zones(val clock: Area, val date: Area, val weather: Area, val today: Area,
                     val tomorrow: Area, val footer: Area, val motionLimit: Float)
    fun zones(width: Float, height: Float, density: Float): Zones {
        val short = minOf(width, height).coerceAtLeast(0f)
        val padding = minOf(16f * density, short * 0.04f)
        val movement = minOf(28f * density, short * 0.08f)
        val inset = padding + movement
        val left = inset; val right = maxOf(left, width - inset)
        val top = inset; val bottom = maxOf(top, height - inset)
        val h = bottom - top; val middle = (left + right) / 2
        val gap = minOf(12f * density, short * 0.025f)
        val topEnd = top + h * 0.24f
        val clockEnd = top + h * 0.76f
        val dutyEnd = top + h * 0.94f
        return Zones(
            Area(left, topEnd + gap / 2, right, clockEnd - gap / 2),
            Area(left, top, middle - gap / 2, topEnd - gap / 2),
            Area(middle + gap / 2, top, right, topEnd - gap / 2),
            Area(left, clockEnd + gap / 2, middle - gap / 2, dutyEnd - gap / 2),
            Area(middle + gap / 2, clockEnd + gap / 2, right, dutyEnd - gap / 2),
            Area(left, dutyEnd, right, bottom), movement
        )
    }
    fun place(width: Float, height: Float, inset: Float, itemWidth: Float, itemHeight: Float, position: ClockPosition): Area {
        val halfWidth = itemWidth / 2; val halfHeight = itemHeight / 2
        val minX = inset + halfWidth; val minY = inset + halfHeight
        val x = (position.x * width).coerceIn(minX, maxOf(minX, width - inset - halfWidth))
        val y = (position.y * height).coerceIn(minY, maxOf(minY, height - inset - halfHeight))
        return Area(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }
    fun infoWidth(width: Float, height: Float, inset: Float, clockWidth: Float, blockHeight: Float): Float {
        val fit = scale(width, height, inset, clockWidth, blockHeight, 100)
        return if (fit > 0f) maxOf(clockWidth, (width - 2 * inset) / fit) else clockWidth
    }
    fun scale(width: Float, height: Float, inset: Float, blockWidth: Float, blockHeight: Float, percent: Int): Float {
        if (blockWidth <= 0 || blockHeight <= 0) return 0f
        val fit = minOf((width - 2 * inset).coerceAtLeast(0f) / blockWidth,
            (height - 2 * inset).coerceAtLeast(0f) / blockHeight)
        return fit * percent.coerceIn(40, 100) / 100f
    }
}
