package com.mirror.app.phone

import android.content.Context
internal class AirRemote(context: Context) : AutoCloseable {
    val eco=false
    fun needsFrame() = false
    fun offer(frame: androidx.camera.core.ImageProxy) {}
    fun refresh() {}
    override fun close() {}
    companion object { fun ensureCamera(activity: android.app.Activity) {}
        fun enabled(context: Context) = false }
}
