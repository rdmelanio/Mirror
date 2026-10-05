package com.mirror.app.phone

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Confined to the single analysis executor. Rotation is metadata, never a second JPEG encode. */
class JpegEncoder {
    private var nv21 = ByteArray(0)
    private var row = ByteArray(0)
    private val bytes = ByteArrayOutputStream(256 * 1024)
    fun encode(image: ImageProxy, quality: Int): ByteArray {
        val crop = image.cropRect
        val left = crop.left and -2; val top = crop.top and -2
        val width = crop.width() and -2; val height = crop.height() and -2
        val size = width * height * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        val planes = image.planes
        val rowSize = planes.maxOf { it.rowStride }
        if (row.size < rowSize) row = ByteArray(rowSize)
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        YuvPlanes.copy(y.buffer, y.rowStride, y.pixelStride, left, top, width, height, nv21, 0, 1, row)
        YuvPlanes.copy(v.buffer, v.rowStride, v.pixelStride, left / 2, top / 2, width / 2, height / 2, nv21, width * height, 2, row)
        YuvPlanes.copy(u.buffer, u.rowStride, u.pixelStride, left / 2, top / 2, width / 2, height / 2, nv21, width * height + 1, 2, row)
        bytes.reset()
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, bytes))
        // Published JPEGs are immutable: senders may still hold an older frame.
        return bytes.toByteArray()
    }
}
