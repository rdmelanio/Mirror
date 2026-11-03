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
    fun image(maxSide:Int,bitmapCompatibility:Boolean=false,roi:AirRoi=AirRoi.FULL):MPImage {
        val uprightW=if(rotation%180==0) width else height
        val uprightH=if(rotation%180==0) height else width
        val (w,h)=AirPixels.dimensions(maxOf(1,(uprightW*roi.width).toInt()),maxOf(1,(uprightH*roi.height).toInt()),0,maxSide)
        if(pixels.size<w*h) { val capacity=maxOf(w*h,maxSide*maxSide);pixels=IntArray(capacity);buffer=ByteBuffer.allocateDirect(capacity*3) }
        AirPixels.convert(yuv,width,height,rotation,w,h,pixels,roi)
        if(bitmapCompatibility) {
            val bitmap=android.graphics.Bitmap.createBitmap(pixels,w,h,android.graphics.Bitmap.Config.ARGB_8888)
            var built=false
            try { return com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build().also { built=true } }
            finally { if(!built) bitmap.recycle() }
        }
        AirPixels.packRows(pixels,buffer!!,w,h)
        return wrap(buffer!!,w,h,packed=false)
    }
    internal fun loadNv21(data:ByteArray,w:Int,h:Int,degrees:Int) {
        require(data.size==w*h*3/2);width=w;height=h;rotation=degrees;yuv=data.copyOf()
    }
    fun close() { buffer=null;yuv=ByteArray(0);pixels=IntArray(0);scratch=ByteArray(0) }
    companion object {
        fun wrap(buffer:ByteBuffer,width:Int,height:Int,packed:Boolean=true):MPImage {
            require(width>0 && height>0)
            val stride=AirPixels.rgbStride(width);val count=stride*height
            val storage=if(packed && stride!=width*3) {
                require(buffer.limit()>=width*height*3)
                ByteBuffer.allocateDirect(count).also { out ->
                    val source=buffer.duplicate()
                    for(row in 0 until height) {
                        source.position(row*width*3);source.limit((row+1)*width*3);out.put(source);source.limit(buffer.limit())
                        repeat(stride-width*3) { out.put(0) }
                    }
                    out.flip()
                }
            } else buffer
            require(storage.capacity()>=count && storage.limit()>=count)
            // PacketCreator checks capacity, not remaining(). A limited duplicate alone is insufficient.
            val exact=storage.duplicate().apply { position(0);limit(count) }.slice()
            check(exact.capacity()==count && exact.position()==0 && exact.limit()==count)
            return ByteBufferImageBuilder(exact,width,height,MPImage.IMAGE_FORMAT_RGB).build()
        }
    }
}
