package com.mirror.app.tv

import org.junit.Assert.*
import org.junit.Test

class DelayedFramesTest {
    @Test fun waitsForDelayAndDropsOldestCompressedFramesAtMemoryCap() {
        val buffer = DelayedFrames(12)
        buffer.add(DelayedFrames.Frame(byteArrayOf(1, 1, 1, 1), 90, 0))
        assertEquals(5L, buffer.remainingSeconds(0, 5000))
        assertNull(buffer.take(4999, 5000))
        assertEquals(90, buffer.take(5000, 5000)!!.rotation)
        repeat(10) { index -> buffer.add(DelayedFrames.Frame(ByteArray(4) { index.toByte() }, 0, index * 100L)) }
        assertEquals(12, buffer.bytes)
        assertEquals(7L * 100, buffer.take(5700, 5000)!!.at)
        buffer.clear(); assertEquals(0, buffer.bytes)
    }
    @Test fun onlySelectedFrameNeedsDecodingAndLargeFramesCannotExceedBudget() {
        val buffer = DelayedFrames(100)
        repeat(10) { buffer.add(DelayedFrames.Frame(byteArrayOf(it.toByte()), 0, it * 33L)) }
        assertEquals(9L * 33, buffer.take(6000, 5000)!!.at)
        assertNull(buffer.take(6001, 5000))
        buffer.add(DelayedFrames.Frame(ByteArray(101), 0, 0)); assertEquals(0, buffer.bytes)
    }
}
