package com.mirror.app.phone

import kotlin.math.hypot

internal enum class AirApp(val packages:List<String>) {
    YOUTUBE(listOf("com.google.android.youtube.tv")),
    NETFLIX(listOf("com.netflix.ninja")),
    STREMIO(listOf("com.stremio.one","com.stremio.two"))
}

/** Geometry is pose evidence, not a calibrated model probability. Reject partial / contradictory poses. */
internal object AirShortcutPose {
    fun classify(p:List<AirPointing.Point>):String? {
        if(p.size!=21 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun d(a:Int,b:Int)=hypot(p[a].x-p[b].x,p[a].y-p[b].y)
        val size=d(0,9);if(size<.003f) return null
        fun extended(m:Int,j:Int,t:Int):Boolean {
            val dot=(p[j].x-p[m].x)*(p[t].x-p[j].x)+(p[j].y-p[m].y)*(p[t].y-p[j].y)
            return d(0,t)>d(0,j)+size*.16f && d(m,t)>d(m,j)*1.35f && dot>0
        }
        fun folded(m:Int,j:Int,t:Int)=d(0,t)<d(0,j)+size*.05f && d(m,t)<size*.85f
        val thumb=d(4,5)>size*.85f && d(4,2)>d(3,2)*1.55f
        val index=extended(5,6,8);val middle=extended(9,10,12);val ring=extended(13,14,16);val pinky=extended(17,18,20)
        return when {
            thumb && pinky && !index && !middle && !ring && folded(5,6,8) && folded(9,10,12) && folded(13,14,16) -> "SHAKA"
            !thumb && index && middle && ring && !pinky && folded(17,18,20) -> "THREE_FINGERS"
            else -> null
        }
    }
}

/** Bounded evidence with one dropout allowance; short fists still serve as neutral releases. */
internal class AirShortcuts {
    private var pose="";private var since=-1L;private var good=-1L;private var samples=0
    private var latched="";private var releaseAt=-1L;private var releaseCount=0
    var pending=false;private set
    var activityAt=-1L;private set
    var intended:AirApp?=null;private set
    var reason="";private set
    fun reset() { pose="";since=-1;good=-1;samples=0;latched="";releaseAt=-1;releaseCount=0;pending=false;intended=null }
    fun pause() { pose="";since=-1;samples=0;pending=false;releaseAt=-1;releaseCount=0 }
    fun update(now:Long,name:String,score:Float,threshold:Float,enabled:Set<AirApp>):AirApp? {
        val target=when(name) { "SHAKA" -> AirApp.YOUTUBE;"THREE_FINGERS" -> AirApp.NETFLIX;"Closed_Fist" -> if(score>=threshold) AirApp.STREMIO else null;else -> null }?.takeIf { it in enabled }
        intended=target
        if(target==null) {
            if(name in setOf("","None") && now-good<=500 && pose.isNotEmpty()) { reason="Shortcut classification dropout";return null }
            if(releaseAt<0) releaseAt=now
            releaseCount++
            if(releaseCount>=2 && now-releaseAt>=250) latched=""
            pose="";samples=0;pending=false;reason="Shortcut neutral";return null
        }
        releaseAt=-1;releaseCount=0
        if(name!=pose || now-good>700) { pose=name;since=now;samples=0 }
        good=now;samples++
        pending=latched!=pose
        val hold=when(target) { AirApp.YOUTUBE -> 1000L;AirApp.NETFLIX -> 1100L;AirApp.STREMIO -> 1450L }
        if(samples==2 && pending) activityAt=now
        reason="${target.name} launch candidate ${now-since}/$hold ms"
        if(pending && samples>=3 && now-since>=hold) { latched=pose;pending=false;activityAt=now;reason="${target.name} launch accepted";return target }
        return null
    }
}
