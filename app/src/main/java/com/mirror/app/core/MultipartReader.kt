package com.mirror.app.core

import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException

/** Bounded parser: bulk Content-Length reads, boundary fallback, or bare JPEG markers. */
class MultipartReader(private val input: InputStream, contentType: String?) {
    private val boundary = Regex("boundary\\s*=\\s*\"?([^\";\\s]+)", RegexOption.IGNORE_CASE)
        .find(contentType.orEmpty())?.groupValues?.get(1)?.let { "--" + it.removePrefix("--") }
    var rotationDegrees: Int? = null
        private set
    private var atBoundary = false
    private var ended = false
    fun nextFrame(): ByteArray {
        if (ended) throw EOFException("Stream ended")
        if (boundary == null) return jpeg()
        var line: String
        if (!atBoundary) do {
            line = line()
            if (line == "$boundary--") throw EOFException("Stream ended")
        } while (line != boundary)
        atBoundary = false
        var length: Int? = null
        var total = 0
        while (true) {
            line = line(); total += line.length
            if (total > 32768) throw IOException("Frame headers too large")
            if (line.isEmpty()) break
            if (line.substringBefore(':').equals("X-Rotation", true)) {
                rotationDegrees = line.substringAfter(':').trim().toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) }
            }
            if (line.substringBefore(':').equals("Content-Length", true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: throw IOException("Invalid frame length")
            }
        }
        if (length == null) return boundaryFrame()
        val count = length
        if (count !in 4..MAX_FRAME) throw IOException("Invalid frame size")
        val bytes = ByteArray(count)
        DataInputStream(input).readFully(bytes)
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
    private fun boundaryFrame(): ByteArray {
        val marker = ("\r\n" + boundary).toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(128 * 1024)
        var matched = 0
        while (out.size() < MAX_FRAME + marker.size) {
            val value = input.read()
            if (value < 0) {
                // Some cameras close immediately after a complete final JPEG.
                val bytes = out.toByteArray()
                if (bytes.size in 4..MAX_FRAME && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) return bytes
                throw EOFException()
            }
            out.write(value)
            matched = if (value == (marker[matched].toInt() and 255)) matched + 1
                else if (value == (marker[0].toInt() and 255)) 1 else 0
            if (matched == marker.size) {
                val ending = line()
                if (ending != "" && ending != "--") throw IOException("Invalid boundary")
                atBoundary = ending.isEmpty(); ended = ending == "--"
                val bytes = out.toByteArray().copyOf(out.size() - marker.size)
                if (bytes.size !in 4..MAX_FRAME || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) throw IOException("Expected JPEG")
                return bytes
            }
        }
        throw IOException("Frame too large")
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
