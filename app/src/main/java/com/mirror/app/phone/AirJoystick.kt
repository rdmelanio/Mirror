package com.mirror.app.phone

import kotlin.math.*

/** Upright, viewer-facing full-camera coordinates; crop motion never enters this state machine. */
internal class AirJoystick {
    private var anchor:AirOwnership.Hand?=null
    private var since=-1L;private var samples=0;private var lastSeen=-1L
    private var latched:AirGestures.Command?=null;private var firedAt=-1L;private var repeatedAt=-1L
    private var requireCenter=false
    private var filtered:AirOwnership.Hand?=null
    var reason="Joystick neutral";private set
    var activityAt=-1L;private set
    var pending=false;private set
    fun reset() { anchor=null;since=-1;samples=0;lastSeen=-1;latched=null;firedAt=-1;filtered=null;pending=false;suspendedAt=-1;resumeSamples=0;requireCenter=false }
    private var suspendedAt=-1L
    private var resumeSamples=0
    val hasAnchor get()=anchor!=null
    fun pause(now:Long) {
        if(suspendedAt<0) {
            suspendedAt=now;anchor=null;filtered=null;since=-1;samples=0
            latched=null;firedAt=-1;repeatedAt=-1;requireCenter=false
        }
        pending=false;reason="Joystick: Tracking paused · fresh center required"
    }
    private var shoulderMode:Boolean?=null
    fun update(now:Long,observed:AirOwnership.Hand,sensitivity:Int):AirGestures.Command? {
        val relative=observed.shoulder!=null
        if(shoulderMode!=null && shoulderMode!=relative) pause(now)
        shoulderMode=relative
        val h=observed.shoulder?.let { observed.copy(x=observed.x-it.x,y=observed.y-it.y) } ?: observed
        pending=false
        if(!h.indexValid || h.gesture in setOf("Thumb_Up","Thumb_Down","Victory","SHAKA","THREE_FINGERS","Closed_Fist","Open_Palm")) {
            // One isolated model dropout can retain the reference, but can never send a key.
            if(h.gesture !in setOf("","None") || now-lastSeen>2400) reset() else pause(now)
            reason="Joystick: Index invalid · pose released / waiting for index";return null
        }
        if(lastSeen>=0 && now-lastSeen>2400) reset()
        if(suspendedAt>=0) {
            if(now-suspendedAt>2400) reset()
            else {
                resumeSamples++
                if(resumeSamples<2) { reason="Joystick: Recovery in progress · waiting for fresh index evidence";return null }
                // Establish a fresh stable center; never replay the old excursion.
                anchor=null;filtered=null;since=-1;samples=0;latched=null;requireCenter=false
                suspendedAt=-1;resumeSamples=0
            }
        }
        lastSeen=now
        if(anchor==null) {
            if(since<0) { since=now;filtered=h }
            if(hypot(h.x-filtered!!.x,h.y-filtered!!.y)>maxOf(.025f,h.size*.6f)) { since=now;samples=0;filtered=h }
            samples++
            if(samples>=2 && now-since>=120) { anchor=h;filtered=h }
            reason=if(anchor==null) "Joystick: Index valid · centering" else "Joystick: Center established · neutral / waiting for movement";return null
        }
        val before=filtered!!
        val f=h.copy(x=before.x*.25f+h.x*.75f,y=before.y*.25f+h.y*.75f)
        filtered=f
        val a=anchor!!;val dx=f.x-a.x;val dy=f.y-a.y
        val threshold=maxOf(.012f,a.size*(1.05f-sensitivity.coerceIn(0,100)*.006f))
        val radius=hypot(dx,dy)
        if(radius<threshold*.45f) {
            if(latched!=null) activityAt=now
            latched=null;requireCenter=false;reason="Joystick: Centered · ready";return null
        }
        if(requireCenter) { reason="Joystick: Recovery verified · return to center before movement";return null }
        val direction=when {
            abs(dx)>threshold && abs(dx)>abs(dy)*1.45f -> if(dx>0) AirGestures.Command.RIGHT else AirGestures.Command.LEFT
            abs(dy)>threshold && abs(dy)>abs(dx)*1.45f -> if(dy>0) AirGestures.Command.DOWN else AirGestures.Command.UP
            else -> null
        }
        pending=direction!=null && latched==null
        // A direction change while outside center is not a fresh excursion.
        if(direction!=null && latched==null) {
            latched=direction;firedAt=now;repeatedAt=now;activityAt=now;pending=false
            reason="Joystick: $direction accepted";return direction
        }
        if(direction==latched && direction!=null && now-firedAt>=1000 && now-repeatedAt>=550) {
            repeatedAt=now;activityAt=now;reason="Joystick: $direction accepted · controlled repeat";return direction
        }
        reason=if(latched!=null) "Joystick: Waiting to recenter · $latched held" else "Joystick: Index valid · movement below threshold ${(maxOf(abs(dx),abs(dy))/threshold*100).toInt().coerceIn(0,999)}% / diagonal"
        return null
    }
}

internal enum class AirNavigation { JOYSTICK, POINTING }
