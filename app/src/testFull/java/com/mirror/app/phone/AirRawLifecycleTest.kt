package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import com.google.mediapipe.framework.image.ByteBufferExtractor

class AirRawLifecycleTest {
    @Test fun actualMediaPipeContainerCanCloseAndReuseOwnedRgbBufferAcrossFrames() {
        val buffer=ByteBuffer.allocateDirect(12)
        repeat(100) { n ->
            AirPixels.pack(intArrayOf(0xff000000.toInt() or n,0xffffffff.toInt(),0xffff0000.toInt(),0xff00ff00.toInt()),buffer)
            val image=AirRawFrame.wrap(buffer,2,2)
            try {
                assertEquals(2,image.width);assertEquals(2,image.height)
                val bytes=ByteBufferExtractor.extract(image)
                assertEquals(n.toByte(),bytes.get(2))
            } finally { image.close() }
            assertEquals(12,buffer.capacity());assertEquals(n.toByte(),buffer.get(2))
        }
    }
}
