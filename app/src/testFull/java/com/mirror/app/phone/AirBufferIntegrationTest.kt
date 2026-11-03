package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.framework.image.ByteBufferExtractor

class AirBufferIntegrationTest {
    private fun libraryPreflight(image:com.google.mediapipe.framework.image.MPImage) {
        try { AndroidPacketCreator(null).createImage(image);fail("Null graph expected at native boundary") }
        catch(e:NullPointerException) { assertTrue(e.stackTrace.any { it.className=="com.google.mediapipe.framework.PacketCreator" && it.methodName=="createImage" }) }
    }
    @Test fun originalExactExceptionIsFromActualLibraryNotAReimplementedValidator() {
        val storage=ByteBuffer.allocateDirect(1228800)
        val image=com.google.mediapipe.framework.image.ByteBufferImageBuilder(storage,640,360,com.google.mediapipe.framework.image.MPImage.IMAGE_FORMAT_RGB).build()
        try {
            try { AndroidPacketCreator(null).createImage(image);fail("Original buffer should fail") }
            catch(e:IllegalArgumentException) { assertEquals("The size of the buffer should be: 691200 but is 1228800",e.message) }
        } finally { image.close() }
    }
    @Test fun actualOwnedYuvToImagePipelineHandlesDimensionsRotationsCropChangesAndReuse() {
        val frame=AirRawFrame();var n=0
        try {
            for((w,h) in listOf(640 to 360,480 to 270,360 to 640)) for(angle in listOf(0,90,180,270)) {
                val data=ByteArray(w*h*3/2) { if(it<w*h) (16+n).toByte() else 128.toByte() };n++
                frame.loadNv21(data,w,h,angle)
                for(roi in listOf(AirRoi.FULL,AirRoi(.13f,.21f,.71f,.79f),AirRoi(.2f,.2f,.61f,.83f),AirRoi.FULL)) {
                    val image=frame.image(640,false,roi)
                    try {
                        val b=ByteBufferExtractor.extract(image)
                        assertTrue(b.isDirect);assertEquals(0,b.position());assertEquals(AirPixels.rgbStride(image.width)*image.height,b.capacity());assertEquals(b.capacity(),b.limit())
                        libraryPreflight(image)
                    } finally { image.close() }
                }
            }
        } finally { frame.close() }
    }
    @Test fun exactSliceRemainsValidUntilClosedAndPaddingContainsNoStalePixels() {
        val pixels=IntArray(15) { 0xff123456.toInt() };val storage=ByteBuffer.allocateDirect(1000)
        repeat(100) {
            AirPixels.packRows(pixels,storage,3,5)
            val image=AirRawFrame.wrap(storage,3,5,packed=false)
            try {
                val b=ByteBufferExtractor.extract(image);assertEquals(60,b.capacity())
                repeat(5) { row -> assertEquals(0x12.toByte(),b.get(row*12));assertEquals(0,b.get(row*12+9).toInt());assertEquals(0,b.get(row*12+11).toInt()) }
                libraryPreflight(image)
            } finally { image.close() }
        }
    }
}
