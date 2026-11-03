package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirHandLockTest {
    @Test fun roiTranslationMaintainsFullCameraPositionAndJoystickNeutrality() {
        val full=AirPointing.Point(.4f,.5f);val j=AirJoystick()
        val crops=listOf(AirRoi.FULL,AirRoi(.2f,.3f,.6f,.7f),AirRoi(.1f,.2f,.8f,.9f))
        repeat(9) { i -> val r=crops[i%3];val p=r.full(AirPointing.Point((full.x-r.left)/r.width,(full.y-r.top)/r.height));assertEquals(full.x,p.x,.00001f);assertEquals(full.y,p.y,.00001f)
            assertNull(j.update(i*300L,AirOwnership.Hand(p.x,p.y,.08f,"POINT_UP",.8f),50)) }
    }
    @Test fun cropBeforeDownsamplingMatchesOriginalYuvPixelsAtAllRotations() {
        val w=8;val h=6;val yuv=ByteArray(w*h*3/2) { if(it<w*h) (16+it*3).toByte() else 128.toByte() }
        for(rotation in listOf(0,90,180,270)) {
            val rw=if(rotation%180==0) w else h;val rh=if(rotation%180==0) h else w
            val all=IntArray(rw*rh);AirPixels.convert(yuv,w,h,rotation,rw,rh,all)
            val roi=AirRoi(1f/rw,1f/rh,(rw-1f)/rw,(rh-1f)/rh)
            val cw=rw-2;val ch=rh-2;val cropped=IntArray(cw*ch)
            AirPixels.convert(yuv,w,h,rotation,cw,ch,cropped,roi)
            for(y in 0 until ch) for(x in 0 until cw) assertEquals("rotation=$rotation x=$x y=$y",all[(y+1)*rw+x+1],cropped[y*cw+x])
        }
    }
    @Test fun rgbBufferCapacityCanBeReusedWithoutPackingStaleTailPixels() {
        val storage=IntArray(16) { 0xffff0000.toInt() };val buffer=java.nio.ByteBuffer.allocateDirect(48)
        AirPixels.pack(storage,buffer,4);assertEquals(12,buffer.remaining())
        AirPixels.pack(storage,buffer,9);assertEquals(27,buffer.remaining())
        AirPixels.pack(storage,buffer,1);assertEquals(3,buffer.remaining())
    }
    @Test fun lockExpandsOnMissAndPeriodicallyScansForHouseholdClaims() {
        val lock=AirHandLock();val p=List(21) { AirPointing.Point(.5f+(it%3)*.01f,.5f+(it/3)*.008f) }
        lock.observe(p,AirHandLock.Mode.AUTO)
        val roi=lock.input(0,AirHandLock.Mode.AUTO,true);assertTrue(roi.width<1);assertTrue(roi.valid())
        lock.observe(null,AirHandLock.Mode.AUTO);assertTrue(lock.input(300,AirHandLock.Mode.AUTO,true).width>roi.width)
        repeat(3) { lock.observe(null,AirHandLock.Mode.AUTO) };assertEquals(AirRoi.FULL,lock.input(1000,AirHandLock.Mode.AUTO,true))
        lock.observe(p,AirHandLock.Mode.DISTANCE);assertEquals(AirRoi.FULL,lock.input(4600,AirHandLock.Mode.DISTANCE,true));assertEquals(AirRoi.FULL,lock.input(5700,AirHandLock.Mode.DISTANCE,true))
        assertTrue(lock.input(6200,AirHandLock.Mode.DISTANCE,true).width<1)
        assertEquals(AirRoi.FULL,lock.input(6300,AirHandLock.Mode.DISTANCE,false))
    }
}
