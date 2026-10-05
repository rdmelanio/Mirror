package com.mirror.app.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException

/** Bounded parser: multipart Content-Length first, JPEG marker fallback for older cameras. */
class MultipartReader(private val input: InputStream, contentType: String?) {
    private val boundary = Regex("boundary\\s*=\\s*\"?([^\";\\s]+)", RegexOption.IGNORE_CASE)
        .find(contentType.orEmpty())?.groupValues?.get(1)?.let { "--" + it.removePrefix("--") }
    fun nextFrame(): ByteArray {
        if (boundary == null) return jpeg()
        var line: String
        do {
            line = line()
            if (line == "$boundary--") throw EOFException("Stream ended")
        } while (line != boundary)
        var length: Int? = null
        var total = 0
        while (true) {
            line = line(); total += line.length
            if (total > 32768) throw IOException("Frame headers too large")
            if (line.isEmpty()) break
            if (line.substringBefore(':').equals("Content-Length", true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: throw IOException("Invalid frame length")
            }
        }
        if (length == null) return jpeg()
        val count = length
        if (count !in 4..MAX_FRAME) throw IOException("Invalid frame size")
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        if (bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) throw IOException("Expected JPEG")
        return bytes
    }
    private fun line(): String {
        val out = ByteArrayOutputStream()
        while (out.size() <= 8192) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == 10) return out.toString("US-ASCII").removeSuffix("\r")
            out.write(b)
        }
        throw IOException("Stream header too large")
    }
    private fun jpeg(): ByteArray {
        var previous = -1
        var scanned = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (previous == 0xff && b == 0xd8) break
            previous = b
            if (++scanned > MAX_FRAME) throw IOException("JPEG not found")
        }
        val out = ByteArrayOutputStream(128 * 1024)
        out.write(0xff); out.write(0xd8); previous = -1
        while (out.size() < MAX_FRAME) {
            val b = input.read()
            if (b < 0) throw EOFException()
            out.write(b)
            if (previous == 0xff && b == 0xd9) return out.toByteArray()
            previous = b
        }
        throw IOException("Frame too large")
    }
    companion object { const val MAX_FRAME = 8 * 1024 * 1024 }
}
