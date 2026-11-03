package com.mirror.app.phone

import android.content.Context
internal class AirRemote(context: Context) : AutoCloseable {
    fun needsFrame() = false
    fun offer(jpeg: ByteArray) {}
    fun refresh() {}
    override fun close() {}
    companion object { fun ensureCamera(activity: android.app.Activity) {}
        fun enabled(context: Context) = false }
}
