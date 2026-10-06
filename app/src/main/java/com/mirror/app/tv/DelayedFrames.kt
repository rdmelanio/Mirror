package com.mirror.app.tv

import java.util.ArrayDeque

/** Compressed JPEGs only. All access is owned by MjpegClient's monitor. */
class DelayedFrames(private val maxBytes: Int = 60 * 1024 * 1024) {
    data class Frame(val bytes: ByteArray, val rotation: Int, val at: Long)
    private val frames = ArrayDeque<Frame>()
    var bytes = 0
        private set
    fun add(frame: Frame) {
        if (frame.bytes.size > maxBytes) { clear(); return }
        frames.addLast(frame); bytes += frame.bytes.size
        while (bytes > maxBytes) removeFirst()
    }
    private fun removeFirst(): Frame = frames.removeFirst().also { bytes -= it.bytes.size }
    /** Skip outdated compressed frames if rendering falls behind; decode one selected frame. */
    fun take(now: Long, delayMs: Long): Frame? {
        val target = now - delayMs
        var selected: Frame? = null
        while (frames.isNotEmpty() && frames.peekFirst().at <= target) selected = removeFirst()
        return selected
    }
    fun remainingSeconds(now: Long, delayMs: Long): Long = frames.peekFirst()?.let {
        ((it.at + delayMs - now).coerceAtLeast(0) + 999) / 1000
    } ?: ((delayMs + 999) / 1000)
    fun clear() { frames.clear(); bytes = 0 }
}
