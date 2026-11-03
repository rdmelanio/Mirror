package com.mirror.app.phone

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Minimal bounded protobuf wire codec; independent implementation of Remote v2's public schema. */
internal object AirWire {
    private fun varint(value: Int): ByteArray {
        require(value >= 0)
        var v = value; val out = ByteArrayOutputStream()
        while (v > 127) { out.write((v and 127) or 128); v = v ushr 7 }; out.write(v)
        return out.toByteArray()
    }
    fun number(field: Int, value: Int) = varint(field shl 3) + varint(value)
    fun bytes(field: Int, value: ByteArray) = varint((field shl 3) or 2) + varint(value.size) + value
    fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun fields(data: ByteArray): Map<Int, ByteArray> {
        val input = data.inputStream(); val result = mutableMapOf<Int, ByteArray>()
        while (input.available() > 0) {
            val tag = readInt(input); require(tag ushr 3 > 0)
            result[tag ushr 3] = when (tag and 7) {
                0 -> rawVarint(input)
                2 -> { val n = readInt(input); require(n in 0..65536 && n <= input.available()); ByteArray(n).also { check(input.read(it) == n || n == 0) } }
                1 -> ByteArray(8).also { check(input.read(it) == 8) }
                5 -> ByteArray(4).also { check(input.read(it) == 4) }
                else -> error("Unsupported TV message")
            }
        }
        return result
    }
    private fun rawVarint(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(10) { index ->
            val b = input.read(); require(b >= 0) { "Truncated TV varint" }
            require(index < 9 || b <= 1) { "Invalid TV varint" }
            out.write(b)
            if (b < 128) return out.toByteArray()
        }
        error("Invalid TV varint")
    }
    fun value(data: ByteArray?) = if (data == null) 0 else readInt(data.inputStream())
    fun readInt(input: InputStream): Int {
        var n = 0
        for (i in 0..4) { val b = input.read(); check(b >= 0) { "TV disconnected" }; require(i < 4 || b <= 7); n = n or ((b and 127) shl (7 * i)); if (b < 128) return n }
        error("Invalid TV frame")
    }
    fun read(input: InputStream): ByteArray {
        val n = readInt(input); require(n in 1..65536) { "TV frame exceeds limit" }
        val data = ByteArray(n); var offset = 0
        while (offset < n) { val count = input.read(data, offset, n - offset); check(count > 0) { "TV disconnected" }; offset += count }
        return data
    }
    fun write(output: OutputStream, data: ByteArray) { require(data.size in 1..65536); output.write(varint(data.size)); output.write(data); output.flush() }
    fun key(command: AirGestures.Command) = bytes(10, number(1, command.key) + number(2, 3))
    fun pairing(field: Int, body: ByteArray) = number(1, 2) + number(2, 200) + bytes(field, body)
}
