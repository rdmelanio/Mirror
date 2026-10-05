package com.mirror.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class TransportTest {
    @Test fun normalizesBareAddressAndPreservesExternalPaths() {
        assertEquals("http://192.168.1.50:8080/video", StreamAddress.normalize(" 192.168.1.50:8080 "))
        assertEquals("http://camera.local:8080/video", StreamAddress.normalize("http://camera.local:8080/"))
        assertEquals("https://camera.local/feed?x=1", StreamAddress.normalize("https://camera.local/feed?x=1"))
        assertEquals("http://[fe80::1]:8080/video", StreamAddress.forHost("fe80::1", 8080))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedSchemes() { StreamAddress.normalize("file:///video") }
    @Test fun readsConsecutiveFramesWithQuotedBoundaryAndMixedCaseHeaders() {
        // Embedded EOI bytes must not truncate a Content-Length delimited JPEG.
        val a = byteArrayOf(-1, -40, 1, -1, -39, 2, -1, -39)
        val b = byteArrayOf(-1, -40, 3, -1, -39)
        val bytes = "--frame\r\ncOnTeNt-LeNgTh: ${a.size}\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a +
            "\r\n--frame\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray() + b + "\r\n--frame--\r\n".toByteArray()
        val reader = MultipartReader(ByteArrayInputStream(bytes), "multipart/x-mixed-replace; boundary=\"frame\"")
        assertArrayEquals(a, reader.nextFrame()); assertArrayEquals(b, reader.nextFrame())
        try { reader.nextFrame(); throw AssertionError("Expected stream end") } catch (_: EOFException) {}
    }
    @Test fun supportsMultipartWithoutLengthAndBareJpegStreams() {
        val jpeg = byteArrayOf(-1, -40, 1, 2, -1, -39)
        val reader = MultipartReader(ByteArrayInputStream("--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + jpeg), "multipart/x-mixed-replace; boundary=frame")
        assertArrayEquals(jpeg, reader.nextFrame())
        assertArrayEquals(jpeg, MultipartReader(ByteArrayInputStream(jpeg), null).nextFrame())
    }
    @Test(expected = IOException::class) fun rejectsOversizeFrameBeforeAllocating() {
        MultipartReader(ByteArrayInputStream("--x\r\nContent-Length: 999999999\r\n\r\n".toByteArray()), "multipart/x-mixed-replace; boundary=x").nextFrame()
    }
    @Test(expected = EOFException::class) fun handlesTruncatedFrame() {
        MultipartReader(ByteArrayInputStream("--x\r\nContent-Length: 10\r\n\r\n".toByteArray() + byteArrayOf(-1, -40)), "multipart/x-mixed-replace; boundary=x").nextFrame()
    }
}
