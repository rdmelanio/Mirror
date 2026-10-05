package com.mirror.app.phone

import java.nio.ByteBuffer

/** Row copies happen in native ByteBuffer code; only chroma interleaving needs a byte-array loop. */
internal object YuvPlanes {
    fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
             width: Int, height: Int, output: ByteArray, offset: Int, outputStride: Int, scratch: ByteArray) {
        val source = buffer.duplicate()
        val base = source.position() + top * rowStride + left * pixelStride
        val rowBytes = (width - 1) * pixelStride + 1
        if (pixelStride == 1 && outputStride == 1 && rowStride == width) {
            source.position(base); source.get(output, offset, width * height)
            return
        }
        for (row in 0 until height) {
            source.position(base + row * rowStride)
            val destination = offset + row * width * outputStride
            if (pixelStride == 1 && outputStride == 1) source.get(output, destination, width)
            else {
                source.get(scratch, 0, rowBytes)
                var src = 0; var dst = destination
                repeat(width) { output[dst] = scratch[src]; src += pixelStride; dst += outputStride }
            }
        }
    }
}
