package com.mirror.app.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvPlanesTest {
    @Test fun copiesPackedLumaWithNonzeroBufferPosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4, 5, 6)).apply { position(1) }
        val output = ByteArray(6)
        YuvPlanes.copy(source, 3, 1, 0, 0, 3, 2, output, 0, 1, ByteArray(3))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), output)
        assertEquals(1, source.position())
    }
    @Test fun cropsPaddedRowsWithoutReadingBeyondFinalPixel() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 99, 99, 99, 10, 11, 12, 99, 20, 21, 22))
        val output = ByteArray(4)
        YuvPlanes.copy(source, 4, 1, 1, 1, 2, 2, output, 0, 1, ByteArray(4))
        assertArrayEquals(byteArrayOf(11, 12, 21, 22), output)
    }
    @Test fun interleavesPlanarAndPixelStrideTwoChroma() {
        val v = ByteBuffer.wrap(byteArrayOf(10, 99, 11, 99, 99, 20, 99, 21))
        val u = ByteBuffer.wrap(byteArrayOf(30, 31, 99, 40, 41))
        val output = ByteArray(8)
        YuvPlanes.copy(v, 5, 2, 0, 0, 2, 2, output, 0, 2, ByteArray(5))
        YuvPlanes.copy(u, 3, 1, 0, 0, 2, 2, output, 1, 2, ByteArray(5))
        assertArrayEquals(byteArrayOf(10, 30, 11, 31, 20, 40, 21, 41), output)
    }
    @Test fun readsCroppedInterleavedChromaWithNonzeroPosition() {
        val source = ByteBuffer.wrap(ByteArray(30) { it.toByte() }).apply { position(2); limit(29) }
        val output = ByteArray(8)
        YuvPlanes.copy(source, 8, 2, 1, 1, 2, 2, output, 0, 2, ByteArray(8))
        assertArrayEquals(byteArrayOf(12, 0, 14, 0, 20, 0, 22, 0), output)
        assertEquals(2, source.position()); assertEquals(29, source.limit())
    }
}
