package com.mirror.app.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.ArrayDeque

/** Only bitmaps no longer used by the renderer enter this pool. */
class BitmapPool {
    private val available = ArrayDeque<Bitmap>()
    private var closed = false
    // Decode is confined to one background thread; avoid options allocations per frame.
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    private val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true }
    @Synchronized fun take(): Bitmap? = if (available.isEmpty()) null else available.removeFirst()
    @Synchronized fun release(bitmap: Bitmap) {
        if (!closed && !bitmap.isRecycled && bitmap.isMutable && available.size < 3) available.addLast(bitmap)
        // Let GC reclaim excess bitmaps; never recycle a hardware renderer's reference.
    }
    @Synchronized fun close() { closed = true; available.clear() }
    fun decode(jpeg: ByteArray): Bitmap? {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2
        val reuse = take()
        options.inSampleSize = sample; options.inBitmap = reuse
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options).also { if (it !== reuse && reuse != null) release(reuse) }
        } catch (_: IllegalArgumentException) {
            // A resolution/configuration change can invalidate inBitmap's allocation.
            options.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } finally { options.inBitmap = null }
    }
}

class VideoFrame(val bitmap: Bitmap, val rotation: Int, private val pool: BitmapPool) {
    fun release() { pool.release(bitmap) }
}
