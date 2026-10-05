package com.mirror.app.phone

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Confined to the analysis executor; reusable YUV buffers and one JPEG encode. */
class JpegEncoder {
    private var nv21 = ByteArray(0)
    private var row = ByteArray(0)
    private var rotated = ByteArray(0)
    private val bytes = ByteArrayOutputStream(256 * 1024)
    fun encode(image: ImageProxy, quality: Int, orientation: String): ByteArray {
        val crop = image.cropRect
        val degrees = image.imageInfo.rotationDegrees
        val region = Nv21Transform.crop(crop.width() and -2, crop.height() and -2, degrees, orientation)
        val left = (crop.left and -2) + region.left; val top = (crop.top and -2) + region.top
        val width = region.width; val height = region.height
        val size = width * height * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        val planes = image.planes
        val rowSize = planes.maxOf { it.rowStride }
        if (row.size < rowSize) row = ByteArray(rowSize)
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        YuvPlanes.copy(y.buffer, y.rowStride, y.pixelStride, left, top, width, height, nv21, 0, 1, row)
        YuvPlanes.copy(v.buffer, v.rowStride, v.pixelStride, left / 2, top / 2, width / 2, height / 2, nv21, width * height, 2, row)
        YuvPlanes.copy(u.buffer, u.rowStride, u.pixelStride, left / 2, top / 2, width / 2, height / 2, nv21, width * height + 1, 2, row)
        val upright = if (degrees == 0) nv21 else {
            if (rotated.size != size) rotated = ByteArray(size)
            Nv21Transform.rotate(nv21, rotated, width, height, degrees)
            rotated
        }
        val sideways = degrees % 180 != 0
        val outWidth = if (sideways) height else width
        val outHeight = if (sideways) width else height
        bytes.reset()
        check(YuvImage(upright, ImageFormat.NV21, outWidth, outHeight, null)
            .compressToJpeg(Rect(0, 0, outWidth, outHeight), quality, bytes))
        // Published JPEGs are immutable: senders may still hold an older frame.
        return bytes.toByteArray()
    }
}

