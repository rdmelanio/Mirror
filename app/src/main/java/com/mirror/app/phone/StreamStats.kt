package com.mirror.app.phone

import java.util.concurrent.atomic.AtomicInteger

class StreamStats {
    private val cameraFrames = AtomicInteger()
    private val sentFrames = AtomicInteger()
    val clients = AtomicInteger()
    @Volatile var cameraFps = 0.0
        private set
    @Volatile var sendFps = 0.0
        private set
    @Volatile var encodeMs = 0.0
        private set
    @Volatile var rotationDegrees = 0
    fun encoded(milliseconds: Double) {
        cameraFrames.incrementAndGet()
        encodeMs = if (encodeMs == 0.0) milliseconds else encodeMs * .9 + milliseconds * .1
    }
    fun sent() { sentFrames.incrementAndGet() }
    fun sample(seconds: Double) {
        cameraFps = cameraFrames.getAndSet(0) / seconds
        // Aggregate completed frame writes across all connected viewers.
        sendFps = sentFrames.getAndSet(0) / seconds
    }
}
