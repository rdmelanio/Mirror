package com.mirror.app.phone

import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer

/** Owned compact YUV and RGB buffers. Single-frame lease lasts until synchronous inference and MPImage close. */
internal class AirRawFrame {
    private var yuv=ByteArray(0)
    private var scratch=ByteArray(0)
    private var pixels=IntArray(0)
    private var buffer:ByteBuffer?=null
    private var width=0;private var height=0;private var rotation=0
    fun copy(image:ImageProxy) {
        width=image.cropRect.width() and -2;height=image.cropRect.height() and -2;rotation=image.imageInfo.rotationDegrees
        val n=width*height*3/2;if(yuv.size!=n) yuv=ByteArray(n)
        val row=image.planes.maxOf { it.rowStride };if(scratch.size<row) scratch=ByteArray(row)
        val left=image.cropRect.left and -2;val top=image.cropRect.top and -2
        val y=image.planes[0];val u=image.planes[1];val v=image.planes[2]
        YuvPlanes.copy(y.buffer,y.rowStride,y.pixelStride,left,top,width,height,yuv,0,1,scratch)
        YuvPlanes.copy(v.buffer,v.rowStride,v.pixelStride,left/2,top/2,width/2,height/2,yuv,width*height,2,scratch)
        YuvPlanes.copy(u.buffer,u.rowStride,u.pixelStride,left/2,top/2,width/2,height/2,yuv,width*height+1,2,scratch)
    }
    fun image(maxSide:Int,bitmapCompatibility:Boolean=false):MPImage {
        val (w,h)=AirPixels.dimensions(width,height,rotation,maxSide)
        if(pixels.size!=w*h) { pixels=IntArray(w*h);buffer=ByteBuffer.allocateDirect(w*h*3) }
        AirPixels.convert(yuv,width,height,rotation,w,h,pixels)
        if(bitmapCompatibility) return com.google.mediapipe.framework.image.BitmapImageBuilder(
            android.graphics.Bitmap.createBitmap(pixels,w,h,android.graphics.Bitmap.Config.ARGB_8888)).build()
        AirPixels.pack(pixels,buffer!!)
        return wrap(buffer!!,w,h)
    }
    fun close() { buffer=null;yuv=ByteArray(0);pixels=IntArray(0);scratch=ByteArray(0) }
    companion object {
        fun wrap(buffer:ByteBuffer,width:Int,height:Int):MPImage =
            ByteBufferImageBuilder(buffer,width,height,MPImage.IMAGE_FORMAT_RGB).build()
    }
}
