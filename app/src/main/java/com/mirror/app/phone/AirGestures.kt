package com.mirror.app.phone

import kotlin.math.abs

/** Monotonic milliseconds and upright, viewer-facing normalized palm coordinates. */
internal class AirGestures {
    enum class Command(val key: Int) { LEFT(21), RIGHT(22), UP(19), DOWN(20), OK(23), BACK(4), HOME(3) }
    var armed = false; private set
    var sensitivity = 50
    var minimumConfidence = .65f
    var movement = "No swipe"; private set
    private var palmAt = -1000L
    private var motionSince = -1L
    private var motionX = 0f
    private var motionY = 0f
    private var lastAction = -1000L
    private var lastSeen = -1L
    private var lastMotion = 0L
    private var candidate = ""
    private var since = 0L
    private var originX = 0f
    private var originY = 0f
    private var latched = false
    private var firedGesture = ""
    fun reset() { armed = false; candidate = ""; latched = false; firedGesture = ""; lastSeen = -1; motionSince=-1; palmAt=-1000;movement="No swipe" }
    fun claim(now: Long, x: Float, y: Float) {
        reset(); armed = true; lastMotion = now; lastSeen = now; candidate = "Open_Palm"
        originX=x;originY=y;since=now;lastAction=now
        // Activation is not a swipe. Require a deliberate release/change before accepting a command.
        latched=true;firedGesture="Open_Palm"
    }
    fun pause(now: Long) { candidate="";since=now;latched=true;firedGesture="";lastSeen=now }
    fun update(now: Long, gesture: String, confidence: Float, x: Float, y: Float, handSize: Float = .14f): Command? {
        if (armed && now - lastMotion >= 30_000) reset()
        if (lastSeen >= 0 && now - lastSeen > 1400) reset()
        lastSeen = now
        val name = if (confidence >= minimumConfidence && x.isFinite() && y.isFinite()) gesture else ""
        if(name=="Open_Palm") palmAt=now
        val movingPalm = name=="Open_Palm" || (name=="" || name=="None") && now-palmAt<=500
        if(movingPalm && motionSince<0) { motionSince=now;motionX=x;motionY=y }
        if(!movingPalm) motionSince=-1
        if (name != candidate) { candidate = name; since = now; originX = x; originY = y }
        if (!armed) {
            if (name == "Open_Palm") {
                if (abs(x - originX) > .07f || abs(y - originY) > .07f) { since = now; originX = x; originY = y }
                if (now - since >= 2000) { claim(now, x, y) }
            }
            return null
        }
        if (latched) {
            if (name != firedGesture && now - since >= 250) { latched = false; firedGesture = ""; motionSince=-1 } else return null
        }
        if (now - lastAction < 750) return null
        val command = when {
            movingPalm && motionSince>=0 && now-motionSince in 100..1600 -> {
                val dx=x-motionX;val dy=y-motionY
                val threshold=(handSize * (2.2f-sensitivity.coerceIn(0,100)*.014f)).coerceIn(.065f,.28f)
                val elapsed=(now-motionSince)/1000f
                val reached=maxOf(abs(dx),abs(dy))>=threshold && maxOf(abs(dx),abs(dy))/elapsed>=.10f
                movement="dx=%.2f dy=%.2f threshold=%.2f %s".format(java.util.Locale.US,dx,dy,threshold,if(reached) "reached" else "pending")
                when {
                    reached && abs(dx)>abs(dy)*1.35f -> if(dx>0) Command.RIGHT else Command.LEFT
                    reached && abs(dy)>abs(dx)*1.35f -> if(dy>0) Command.DOWN else Command.UP
                    else -> null
                }
            }
            now - since >= 300 -> when (name) {
                "Closed_Fist" -> Command.OK
                "Victory" -> Command.BACK
                "Thumb_Up" -> Command.HOME
                else -> null
            }
            else -> null
        }
        if(motionSince>=0 && now-motionSince>1600) { motionSince=now;motionX=x;motionY=y }
        if (command != null) { lastAction = now; lastMotion = now; latched = true; firedGesture = name }
        return command
    }
}
