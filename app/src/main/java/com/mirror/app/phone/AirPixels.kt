package com.mirror.app.phone

/** Downsample compact NV21 directly into upright RGB. No JPEG, full-size RGB or rotation bitmap. */
internal object AirPixels {
    fun pack(pixels:IntArray,buffer:java.nio.ByteBuffer) {
        require(buffer.capacity()>=pixels.size*3)
        buffer.clear()
        for(c in pixels) { buffer.put((c ushr 16).toByte());buffer.put((c ushr 8).toByte());buffer.put(c.toByte()) }
        buffer.flip()
    }
    fun dimensions(w:Int,h:Int,rotation:Int,maxSide:Int):Pair<Int,Int> {
        val sideways=rotation%180!=0;val rw=if(sideways) h else w;val rh=if(sideways) w else h
        val scale=minOf(1.0,maxSide.toDouble()/maxOf(rw,rh))
        return maxOf(1,(rw*scale).toInt()) to maxOf(1,(rh*scale).toInt())
    }
    fun convert(data:ByteArray,w:Int,h:Int,rotation:Int,outW:Int,outH:Int,rgb:IntArray) {
        require(w%2==0 && h%2==0 && rotation in listOf(0,90,180,270) && data.size>=w*h*3/2 && rgb.size>=outW*outH)
        val rw=if(rotation%180==0) w else h;val rh=if(rotation%180==0) h else w
        for(oy in 0 until outH) for(ox in 0 until outW) {
            val rx=ox*rw/outW;val ry=oy*rh/outH
            val x=when(rotation) { 90 -> ry;180 -> w-1-rx;270 -> w-1-ry;else -> rx }
            val y=when(rotation) { 90 -> h-1-rx;180 -> h-1-ry;270 -> rx;else -> ry }
            val yy=(data[y*w+x].toInt() and 255)-16
            val uv=w*h+(y/2)*w+(x/2)*2
            val v=(data[uv].toInt() and 255)-128;val u=(data[uv+1].toInt() and 255)-128
            val c=298*yy.coerceAtLeast(0)
            val r=((c+409*v+128) shr 8).coerceIn(0,255)
            val g=((c-100*u-208*v+128) shr 8).coerceIn(0,255)
            val b=((c+516*u+128) shr 8).coerceIn(0,255)
            rgb[oy*outW+ox]=(0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
