package com.mirror.app.phone

/** Keeps alert information readable and the acknowledgement control separate in either orientation. */
object EcamLayout {
    data class Regions(val header: ClockLayout.Area, val duty: ClockLayout.Area, val action: ClockLayout.Area,
                       val stop: ClockLayout.Area, val footer: ClockLayout.Area, val inset: Float, val gap: Float)
    fun regions(width: Float, height: Float, density: Float): Regions {
        val short = minOf(width, height)
        val inset = minOf(24f * density, short * 0.075f)
        val gap = minOf(12f * density, short * 0.03f)
        val l = inset; val r = width - inset; val t = inset; val b = height - inset
        val h = b-t; val headerEnd = t + h * 0.18f; val contentEnd = t + h * 0.71f
        val wide = width > height * 1.2f
        val midX = l + (r-l) * .54f; val midY = headerEnd + (contentEnd-headerEnd) * .50f
        return Regions(
            ClockLayout.Area(l, t, r, headerEnd-gap),
            ClockLayout.Area(l, headerEnd+gap, if (wide) midX-gap else r, if (wide) contentEnd else midY-gap),
            ClockLayout.Area(if (wide) midX+gap else l, if (wide) headerEnd+gap else midY+gap, r, contentEnd),
            ClockLayout.Area(l, contentEnd+gap, r, t+h*.91f),
            ClockLayout.Area(l, t+h*.93f, r, b), inset, gap)
    }
}
