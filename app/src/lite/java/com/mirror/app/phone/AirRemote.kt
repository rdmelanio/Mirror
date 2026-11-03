package com.mirror.app.phone

import android.content.Context
internal class AirRemote(context: Context) : AutoCloseable {
    fun offer(jpeg: ByteArray) {}
    fun refresh() {}
    override fun close() {}
    companion object { fun enabled(context: Context) = false }
}
