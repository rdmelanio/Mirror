package com.mirror.app.phone

/** Fits the entire moving layout, preserving padding even at maximum zoom. */
object ClockLayout {
    fun scale(width: Float, height: Float, inset: Float, blockWidth: Float, blockHeight: Float, percent: Int): Float {
        if (blockWidth <= 0 || blockHeight <= 0) return 0f
        val fit = minOf((width - 2 * inset).coerceAtLeast(0f) / blockWidth,
            (height - 2 * inset).coerceAtLeast(0f) / blockHeight)
        return fit * percent.coerceIn(40, 100) / 100f
    }
}
