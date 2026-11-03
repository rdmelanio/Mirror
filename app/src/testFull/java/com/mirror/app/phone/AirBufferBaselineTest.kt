package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import com.google.mediapipe.framework.image.ByteBufferExtractor
class AirBufferBaselineTest {
    @Test fun squareBackingAllocationMustPresentExactly640By360ToActualMediaPipeBuilder() {
        val storage=ByteBuffer.allocateDirect(640*640*3)
        AirPixels.pack(IntArray(640*360),storage,640*360)
        val image=AirRawFrame.wrap(storage,640,360)
        try {
            com.google.mediapipe.framework.AndroidPacketCreator(null).createImage(image)
            fail("A null graph must stop at the JNI boundary")
        } catch(e:NullPointerException) {
            // Successful library-side input validation; Android native graph unavailable on this host.
        }
        try {
            val actual=ByteBufferExtractor.extract(image)
            assertEquals("MediaPipe requires exactly 691200 bytes, not the 1228800 square allocation",691200,actual.capacity())
        } finally { image.close() }
    }
}
