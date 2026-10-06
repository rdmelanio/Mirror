package com.mirror.app.phone

/** Monotonic durations only: roster, time zone and wall-clock changes cannot trigger night. */
class ClockLightPolicy {
    var lux: Float? = null; private set
    var night = false; private set
    private var lowSince: Long? = null
    private var highSince: Long? = null
    private var darkSince: Long? = null
    private var lastViewed = Long.MIN_VALUE
    fun sample(value: Float, now: Long) {
        if (!value.isFinite() || value < 0) return
        lux = lux?.let { it * 0.85f + value * 0.15f } ?: value
        if (value < 5f) {
            if (lowSince == null) lowSince = now
            if (darkSince == null) darkSince = now
        } else { lowSince = null; darkSince = null }
        if (value > 15f) { if (highSince == null) highSince = now } else highSince = null
        // Away resumes immediately above the night threshold, without the day debounce.
        if (value > 5f) darkSince = null
        advance(now, false, Long.MIN_VALUE)
    }
    fun advance(now: Long, live: Boolean, viewedAt: Long) {
        if (live) lastViewed = now
        lastViewed = maxOf(lastViewed, viewedAt)
        if (lowSince?.let { now - it >= 60_000 } == true) night = true
        if (highSince?.let { now - it >= 60_000 } == true) night = false
    }
    fun blank(s: ClockSettings, now: Long): Boolean {
        val dark = darkSince ?: return false
        return s.away && now - maxOf(dark, lastViewed) >= s.awayHours * 3_600_000L
    }
    fun brightness(s: ClockSettings, live: Boolean, now: Long): Float {
        if (blank(s, now) || (s.autoNight && night)) return 0.01f
        val cap = s.maxBrightness / 100f
        val level = if (s.autoBrightness && lux != null)
            (0.01f + 0.59f * (kotlin.math.ln(1f + lux!!) / kotlin.math.ln(501f))).coerceIn(0.01f, cap)
        else cap
        return (level * if (live && s.dimLive) 0.5f else 1f).coerceAtLeast(0.01f)
    }
    fun nextDeadline(now: Long, s: ClockSettings): Long? = listOfNotNull(
        lowSince?.takeIf { !night }?.plus(60_000), highSince?.takeIf { night }?.plus(60_000),
        darkSince?.takeIf { s.away }?.let { maxOf(it, lastViewed) + s.awayHours * 3_600_000L }
    ).filter { it > now }.minOrNull()
}

object ClockWords {
    fun time(hour: Int, minute: Int): String {
        val names = listOf("TWELVE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "ELEVEN")
        val step = minute / 5
        val h = names[(hour + if (step > 6) 1 else 0) % 12]
        val phrase = listOf("", "FIVE", "TEN", "QUARTER", "TWENTY", "TWENTY FIVE", "HALF")
        return if (step == 0) "IT IS $h O'CLOCK"
        else "IT IS ${phrase[if (step <= 6) step else 12 - step]} ${if (step <= 6) "PAST" else "TO"} $h"
    }
}
