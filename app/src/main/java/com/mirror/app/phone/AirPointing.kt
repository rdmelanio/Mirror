package com.mirror.app.phone

import kotlin.math.*

internal object AirPointing {
    data class Point(val x:Float,val y:Float)
    fun quality(p:List<Point>):Float {
        if(p.size!=21) return 0f
        val ax=p[6].x-p[5].x;val ay=p[6].y-p[5].y;val bx=p[8].x-p[6].x;val by=p[8].y-p[6].y
        return ((ax*bx+ay*by)/(hypot(ax,ay)*hypot(bx,by)).coerceAtLeast(.00001f)).coerceIn(0f,1f)
    }
    fun classify(p:List<Point>,mirrored:Boolean):String? {
        if(p.size!=21 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun d(a:Int,b:Int)=hypot(p[a].x-p[b].x,p[a].y-p[b].y)
        val size=d(0,9);if(size<.015f) return null
        fun extended(mcp:Int,pip:Int,tip:Int):Boolean {
            val ax=p[pip].x-p[mcp].x;val ay=p[pip].y-p[mcp].y
            val bx=p[tip].x-p[pip].x;val by=p[tip].y-p[pip].y
            return d(0,tip)>d(0,pip)+size*.18f && ax*bx+ay*by>0 && d(mcp,tip)>d(mcp,pip)*1.35f
        }
        if(!extended(5,6,8) || listOf(Triple(9,10,12),Triple(13,14,16),Triple(17,18,20)).any { extended(it.first,it.second,it.third) }) return null
        // An extended thumb plus index is a transition/ambiguous pose, not pointing.
        if(d(4,5)>size*.95f && d(4,2)>d(3,2)*1.6f) return null
        val dx=(p[8].x-p[5].x)*(if(mirrored) -1 else 1);val dy=p[8].y-p[5].y
        if(hypot(dx,dy)<size*.6f) return null
        return when {
            abs(dx)>abs(dy)*1.6f -> if(dx>0) "POINT_RIGHT" else "POINT_LEFT"
            abs(dy)>abs(dx)*1.6f -> if(dy>0) "POINT_DOWN" else "POINT_UP"
            else -> null
        }
    }
}

internal class AirPointCommands {
    private var candidate="";private var since=0L;private var samples=0
    private var latched="";private var releaseAt=-1L
    private var last=-1000L
    var holdMs=400L
    var reason="Neutral";private set
    fun reset() { candidate="";samples=0;latched="";releaseAt=-1;last=-1000 }
    fun pause() { candidate="";samples=0;releaseAt=-1 }
    fun update(now:Long,name:String,confidence:Float,threshold:Float):AirGestures.Command? {
        val pose=if(confidence>=threshold) name else ""
        if(pose!=candidate) { candidate=pose;since=now;samples=0 };samples++
        if(latched.isNotEmpty()) {
            if(pose!=latched && pose.isNotEmpty() && pose!="None") {
                if(releaseAt<0) releaseAt=now
                if(now-releaseAt>=200) { latched="";releaseAt=-1 }
            } else releaseAt=-1
        }
        val command=when(pose) {
            "POINT_UP" -> AirGestures.Command.UP;"POINT_DOWN" -> AirGestures.Command.DOWN
            "POINT_LEFT" -> AirGestures.Command.LEFT;"POINT_RIGHT" -> AirGestures.Command.RIGHT
            "Thumb_Up" -> AirGestures.Command.OK;"Thumb_Down" -> AirGestures.Command.BACK;"Victory" -> AirGestures.Command.HOME
            else -> null
        }
        val dwell=if(pose=="Victory") maxOf(800L,holdMs) else holdMs.coerceIn(250,1200)
        reason=when { command==null -> "Neutral / release";pose==latched -> "Held pose · release before repeating";else -> "$pose · dwell ${(now-since).coerceAtLeast(0)}/$dwell ms" }
        if(command!=null && pose!=latched && samples>=2 && now-since>=dwell && now-last>=650) {
            latched=pose;releaseAt=-1;last=now;reason="Command accepted · $command";return command
        }
        return null
    }
}
