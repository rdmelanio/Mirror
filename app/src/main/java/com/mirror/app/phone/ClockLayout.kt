package com.mirror.app.phone

/** Fits the entire moving layout, preserving padding even at maximum zoom. */
object ClockLayout {
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
