package com.mirror.app.phone

import kotlin.math.*

internal object AirPointing {
    data class Point(val x:Float,val y:Float)
    fun quality(p:List<Point>):Float {
        if(p.size!=21) return 0f
        val ax=p[6].x-p[5].x;val ay=p[6].y-p[5].y;val bx=p[8].x-p[6].x;val by=p[8].y-p[6].y
        return ((ax*bx+ay*by)/(hypot(ax,ay)*hypot(bx,by)).coerceAtLeast(.00001f)).coerceIn(0f,1f)
    }
    fun classify(p:List<Point>,mirrored:Boolean):String? = inspect(p,mirrored,false)
    /** Custom valid geometry wins. Native UP is allowed only with upright, single-index corroboration. */
    fun resolve(p:List<Point>,mirrored:Boolean,native:String,confidence:Float,threshold:Float):String {
        classify(p,mirrored)?.let { return it }
        if(native!="Pointing_Up") return native
        return if(confidence>=threshold && inspect(p,mirrored,true)=="POINT_UP") native else ""
    }
    private fun inspect(p:List<Point>,mirrored:Boolean,relaxed:Boolean):String? {
        if(p.size!=21 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun d(a:Int,b:Int)=hypot(p[a].x-p[b].x,p[a].y-p[b].y)
        val size=d(0,9);if(size<.015f) return null
        fun extended(mcp:Int,pip:Int,tip:Int):Boolean {
            val ax=p[pip].x-p[mcp].x;val ay=p[pip].y-p[mcp].y
            val bx=p[tip].x-p[pip].x;val by=p[tip].y-p[pip].y
            return d(0,tip)>d(0,pip)+size*(if(relaxed) .05f else .18f) && ax*bx+ay*by>0 && d(mcp,tip)>d(mcp,pip)*(if(relaxed) 1.15f else 1.35f)
        }
        if(!extended(5,6,8) || listOf(Triple(9,10,12),Triple(13,14,16),Triple(17,18,20)).any { extended(it.first,it.second,it.third) }) return null
        // An extended thumb plus index is a transition/ambiguous pose, not pointing.
        if(d(4,5)>size*.95f && d(4,2)>d(3,2)*1.6f) return null
        val dx=(p[8].x-p[5].x)*(if(mirrored) -1 else 1);val dy=p[8].y-p[5].y
        if(hypot(dx,dy)<size*(if(relaxed) .45f else .6f)) return null
        return when {
            abs(dx)>abs(dy)*1.6f -> if(dx>0) "POINT_RIGHT" else "POINT_LEFT"
            abs(dy)>abs(dx)*1.6f -> if(dy>0) "POINT_DOWN" else "POINT_UP"
            else -> null
        }
    }
}

internal class AirPointCommands {
    private var candidate="";private var since=0L;private var samples=0
    private var latched="";private var releaseAt=-1L;private var releaseSamples=0
    private var last=-1000L;private var previousAt=-1L;private var stablePose=""
    var activityAt=-1L;private set
    var activityStartedAt=-1L;private set
    var holdMs=400L
    var reason="Neutral";private set
    fun reset() { candidate="";samples=0;latched="";releaseAt=-1;releaseSamples=0;last=-1000;previousAt=-1;stablePose="";activityAt=-1;activityStartedAt=-1 }
    fun pause() { candidate="";samples=0;releaseAt=-1;releaseSamples=0;previousAt=-1 }
    fun update(now:Long,name:String,confidence:Float,threshold:Float):AirGestures.Command? {
        val custom=name in setOf("POINT_UP","POINT_DOWN","POINT_LEFT","POINT_RIGHT")
        // Custom labels are emitted only by validated geometry. Model probabilities gate model labels.
        val pose=if(custom) name else if(confidence>=threshold) { if(name=="Pointing_Up") "POINT_UP" else name } else ""
        val gap=if(previousAt<0) 0 else now-previousAt;previousAt=now
        if(pose!=candidate || gap>900) { candidate=pose;since=now;samples=0 };samples++
        var released=false
        if(latched.isNotEmpty()) {
            val neutralObservation=name in setOf("","None","Open_Palm","Closed_Fist")
            if(pose!=latched && (pose.isNotEmpty() || neutralObservation)) {
                if(releaseAt<0 || gap>900) { releaseAt=now;releaseSamples=0 }
                releaseSamples++
                val neutral=pose.isEmpty() || pose=="None"
                if(releaseSamples>=2 && now-releaseAt>=if(neutral) 300 else 200) {
                    latched="";releaseAt=-1;releaseSamples=0;stablePose="";released=true
                }
            } else { releaseAt=-1;releaseSamples=0 }
        }
        val command=when(pose) {
            "POINT_UP" -> AirGestures.Command.UP;"POINT_DOWN" -> AirGestures.Command.DOWN
            "POINT_LEFT" -> AirGestures.Command.LEFT;"POINT_RIGHT" -> AirGestures.Command.RIGHT
            "Thumb_Up" -> AirGestures.Command.OK;"Thumb_Down" -> AirGestures.Command.BACK;"Victory" -> AirGestures.Command.HOME
            else -> null
        }
        if(command!=null && samples>=2 && now-since>=200 && stablePose!=pose) { stablePose=pose;activityAt=now;activityStartedAt=since }
        val dwell=if(pose=="Victory") maxOf(800L,holdMs) else holdMs.coerceIn(250,1200)
        reason=when {
            released -> "Gesture released · ready"
            command==null -> if(latched.isEmpty()) "Gesture released · ready" else "Classification / neutral uncertain · waiting for deliberate release evidence"
            pose==latched -> "Gesture held · waiting for release"
            else -> "$pose detected · waiting for pose stability ${(now-since).coerceAtLeast(0)}/$dwell ms"
        }
        if(command!=null && pose!=latched && samples>=2 && now-since>=dwell && now-last>=650) {
            latched=pose;releaseAt=-1;releaseSamples=0;last=now;reason="Command accepted · $command";return command
        }
        return null
    }
}
