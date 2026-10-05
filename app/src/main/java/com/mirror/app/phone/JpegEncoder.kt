package com.mirror.app.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object JpegEncoder {
    /** Reads row/pixel strides independently; works for planar and interleaved YUV. */
    fun encode(image: ImageProxy): ByteArray {
        val crop = image.cropRect
        val left = crop.left and -2; val top = crop.top and -2
        val width = crop.width() and -2; val height = crop.height() and -2
        val nv21 = ByteArray(width * height * 3 / 2)
        val planes = image.planes
        var output = 0
        for (row in 0 until height) {
            val y = planes[0]; val buffer = y.buffer.duplicate()
            val start = buffer.position() + (top + row) * y.rowStride + left * y.pixelStride
            for (col in 0 until width) nv21[output++] = buffer.get(start + col * y.pixelStride)
        }
        val u = planes[1]; val v = planes[2]
        val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
        for (row in 0 until height / 2) {
            val us = ub.position() + (top / 2 + row) * u.rowStride + left / 2 * u.pixelStride
            val vs = vb.position() + (top / 2 + row) * v.rowStride + left / 2 * v.pixelStride
            for (col in 0 until width / 2) {
                nv21[output++] = vb.get(vs + col * v.pixelStride)
                nv21[output++] = ub.get(us + col * u.pixelStride)
            }
        }
        val bytes = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 70, bytes))
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bytes.toByteArray()
        val original = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size()) ?: error("JPEG decode failed")
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, false)
        bytes.reset(); rotated.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
        if (rotated !== original) rotated.recycle()
        original.recycle()
        return bytes.toByteArray()
    }
}
