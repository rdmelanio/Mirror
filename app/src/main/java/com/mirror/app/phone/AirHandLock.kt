package com.mirror.app.phone

import kotlin.math.*

/** ROI is always in upright, unmirrored full-camera coordinates, independent of inference resolution. */
internal data class AirRoi(val left:Float=0f,val top:Float=0f,val right:Float=1f,val bottom:Float=1f) {
    val width get()=right-left
    val height get()=bottom-top
    fun full(p:AirPointing.Point)=AirPointing.Point(left+p.x*width,top+p.y*height)
    fun valid()=listOf(left,top,right,bottom).all { it.isFinite() } && left>=0 && top>=0 && right<=1 && bottom<=1 && width>0 && height>0
    companion object { val FULL=AirRoi() }
}
internal class AirHandLock {
    enum class Mode { AUTO, WIDE, DISTANCE }
    private var roi=AirRoi.FULL
    private var scanAt=-1L;private var scanUntil=-1L;private var misses=0
    var state="Full frame · searching";private set
    fun reset() { roi=AirRoi.FULL;scanAt=-1;scanUntil=-1;misses=0;state="Full frame · searching" }
    fun input(now:Long,mode:Mode,tracking:Boolean):AirRoi {
        if(mode==Mode.WIDE || !tracking) { state="Full frame · recovery / household discovery";return AirRoi.FULL }
        // A bounded wide burst gives multi-frame one-second claims a chance; no second recognizer.
        if(scanAt<0) scanAt=now
        if(now-scanAt>=4500) { scanAt=now;scanUntil=now+1400 }
        if(now<scanUntil) { state="Full frame · household scan";return AirRoi.FULL }
        state=if(roi==AirRoi.FULL) "Full frame · tracking" else if(misses>0) "Hand crop · recovering" else "Hand crop · tracking"
        return roi
    }
    fun observe(points:List<AirPointing.Point>?,mode:Mode) {
        if(points==null || points.size!=21) {
            misses++
            roi=if(misses>=3) AirRoi.FULL else box((roi.left+roi.right)/2,(roi.top+roi.bottom)/2,roi.width*1.6f,roi.height*1.6f)
            return
        }
        misses=0
        val minX=points.minOf { it.x };val maxX=points.maxOf { it.x };val minY=points.minOf { it.y };val maxY=points.maxOf { it.y }
        val span=maxOf(maxX-minX,maxY-minY)
        if(mode==Mode.WIDE || mode==Mode.AUTO && span>.32f) { roi=AirRoi.FULL;return }
        // Expanded hand box retains finger tips and a comfortable whole-hand joystick excursion.
        val w=maxOf(.16f,(maxX-minX)*3.8f,span*2.7f).coerceAtMost(1f)
        val h=maxOf(.16f,(maxY-minY)*3.8f,span*2.7f).coerceAtMost(1f)
        val target=box((minX+maxX)/2,(minY+maxY)/2,w,h)
        roi=if(roi==AirRoi.FULL) target else AirRoi(roi.left*.35f+target.left*.65f,roi.top*.35f+target.top*.65f,roi.right*.35f+target.right*.65f,roi.bottom*.35f+target.bottom*.65f)
    }
    private fun box(x:Float,y:Float,w:Float,h:Float):AirRoi {
        val rw=w.coerceIn(.02f,1f);val rh=h.coerceIn(.02f,1f)
        val l=(x-rw/2).coerceIn(0f,1f-rw);val t=(y-rh/2).coerceIn(0f,1f-rh)
        return AirRoi(l,t,minOf(1f,l+rw),minOf(1f,t+rh))
    }
}
