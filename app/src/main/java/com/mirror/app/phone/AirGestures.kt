package com.mirror.app.phone

import kotlin.math.*

/** Command latches never own or end the control session. Coordinates are upright and viewer-facing. */
internal class AirGestures {
    enum class Command(val key:Int) { LEFT(21),RIGHT(22),UP(19),DOWN(20),OK(23),BACK(4),HOME(3) }
    var armed=false;private set
    var sensitivity=50
    var minimumConfidence=.65f
    var trackingGraceMs=1400L
    var movement="No swipe";private set
    private var lastAction=-1000L
    private var lastMotion=0L
    private var lastSeen=-1L
    private var candidate=""
    private var since=0L
    private var fired=""
    private var releaseSince=-1L
    private var anchorX=0f
    private var anchorY=0f
    private var anchorAt=-1L
    private var palmAt=-1000L
    private var returning=false
    private var centeredAt=-1L
    private data class Point(val at:Long,val x:Float,val y:Float)
    private val history=java.util.ArrayDeque<Point>()
    fun reset() { armed=false;candidate="";fired="";releaseSince=-1;anchorAt=-1;lastSeen=-1;returning=false;centeredAt=-1;history.clear();movement="Disarmed" }
    fun claim(now:Long,x:Float,y:Float) {
        reset();armed=true;lastMotion=now;lastAction=now-750;lastSeen=now
        candidate="Open_Palm";since=now;anchorX=x;anchorY=y;anchorAt=now;palmAt=now;history.add(Point(now,x,y))
        movement="Active · stabilize palm"
    }
    fun accept(now:Long) { lastMotion=now }
    fun activity(now:Long) { lastMotion=now }
    fun expired(now:Long)=armed && now-lastMotion>=30_000
    fun pause(now:Long) { if(!returning) anchorAt=-1;history.clear();candidate="";since=now;movement="Tracking paused · commands paused" }
    fun update(now:Long,gesture:String,confidence:Float,x:Float,y:Float,handSize:Float=.14f):Command? {
        if(expired(now) || lastSeen>=0 && now-lastSeen>trackingGraceMs) reset()
        lastSeen=now
        val name=if(confidence>=minimumConfidence && x.isFinite() && y.isFinite()) gesture else ""
        if(name!=candidate) { candidate=name;since=now }
        if(!armed) {
            // Compatibility for standalone callers; production claiming uses AirOwnership temporal evidence.
            if(name=="Open_Palm") {
                if(anchorAt<0 || hypot(x-anchorX,y-anchorY)>.07f) { anchorX=x;anchorY=y;anchorAt=now }
                if(now-anchorAt>=2000) claim(now,x,y)
            } else anchorAt=-1
            return null
        }
        if(name=="Open_Palm") palmAt=now
        if(fired.isNotEmpty()) {
            val released=name!=fired
            if(released) { if(releaseSince<0) releaseSince=now } else releaseSince=-1
            if(releaseSince>=0 && now-releaseSince>=250) { fired="";releaseSince=-1 }
        }
        val palm=name=="Open_Palm" || (name.isEmpty() || name=="None") && now-palmAt<=700
        val threshold=(handSize*(2.2f-sensitivity.coerceIn(0,100)*.014f)).coerceIn(.065f,.28f)
        val dead=(threshold*.4f).coerceAtLeast(.035f)
        if(palm) {
            if(anchorAt<0) { anchorAt=now;anchorX=x;anchorY=y;history.clear();history.add(Point(now,x,y));return null }
            val dx=x-anchorX;val dy=y-anchorY
            if(returning) {
                if(hypot(dx,dy)<=dead) {
                    if(centeredAt<0) centeredAt=now
                    if(now-centeredAt>=200) { returning=false;centeredAt=-1;anchorAt=now;anchorX=x;anchorY=y;history.clear() }
                } else centeredAt=-1
                movement="Swipe accepted · return to center; return motion suppressed"
                return null
            }
            if(hypot(dx,dy)<=dead) {
                history.clear();history.add(Point(now,anchorX,anchorY));movement="Palm stable · dead zone";return null
            }
            history.add(Point(now,x,y));while(history.size>1 && now-history.first.at>1600) history.removeFirst()
            val first=history.first
            val elapsed=(now-first.at).coerceAtLeast(1)/1000f
            val mx=x-first.x;val my=y-first.y
            val reached=maxOf(abs(mx),abs(my))>=threshold && maxOf(abs(mx),abs(my))/elapsed>=.10f
            movement="dx=%.2f dy=%.2f threshold=%.2f %s".format(java.util.Locale.US,mx,my,threshold,if(reached) "reached" else "dead zone / slow")
            if(now-lastAction<750 || now-anchorAt<200 || !reached) return null
            val command=when {
                abs(mx)>abs(my)*1.35f -> if(mx>0) Command.RIGHT else Command.LEFT
                abs(my)>abs(mx)*1.35f -> if(my>0) Command.DOWN else Command.UP
                else -> null
            }
            if(command!=null) { lastAction=now;lastMotion=now;returning=true;centeredAt=-1;return command }
            return null
        }
        anchorAt=-1;returning=false;centeredAt=-1;history.clear()
        if(name.isEmpty() || name=="None") { movement="Gesture rejected · confidence / classification";return null }
        if(fired==name || now-since<300 || now-lastAction<750) { movement="Gesture rejected · dwell, held gesture or cooldown";return null }
        val command=when(name) { "Closed_Fist" -> Command.OK;"Victory" -> Command.BACK;"Thumb_Up" -> Command.HOME;else -> null }
        if(command!=null) { fired=name;releaseSince=-1;lastAction=now;lastMotion=now;movement="Command accepted · $command" }
        return command
    }
}
