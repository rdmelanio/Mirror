package com.mirror.app.phone

import kotlin.math.abs

/** Monotonic milliseconds and upright, viewer-facing normalized palm coordinates. */
internal class AirGestures {
    enum class Command(val key: Int) { LEFT(21), RIGHT(22), UP(19), DOWN(20), OK(23), BACK(4), HOME(3) }
    var armed = false; private set
    var sensitivity = 50
    private var lastAction = -1000L
    private var lastSeen = -1L
    private var lastMotion = 0L
    private var candidate = ""
    private var since = 0L
    private var originX = 0f
    private var originY = 0f
    private var latched = false
    private var firedGesture = ""
    fun reset() { armed = false; candidate = ""; latched = false; firedGesture = ""; lastSeen = -1 }
    fun update(now: Long, gesture: String, confidence: Float, x: Float, y: Float): Command? {
        if (armed && now - lastMotion >= 15_000) reset()
        if (lastSeen >= 0 && now - lastSeen > 500) reset()
        lastSeen = now
        val name = if (confidence >= .75f && x.isFinite() && y.isFinite()) gesture else ""
        if (name != candidate) { candidate = name; since = now; originX = x; originY = y }
        if (!armed) {
            if (name == "Open_Palm") {
                if (abs(x - originX) > .07f || abs(y - originY) > .07f) { since = now; originX = x; originY = y }
                if (now - since >= 900) { armed = true; lastMotion = now; originX = x; originY = y; since = now }
            }
            return null
        }
        if (latched) {
            if (name != firedGesture && now - since >= 250) { latched = false; firedGesture = "" } else return null
        }
        if (now - lastAction < 750) return null
        val command = when {
            name == "Open_Palm" && now - since in 100..1200 -> {
                val dx = x - originX; val dy = y - originY
                val threshold = .28f - sensitivity.coerceIn(0, 100) * .0018f
                when {
                    abs(dx) >= threshold && abs(dx) > abs(dy) * 1.4f -> if (dx > 0) Command.RIGHT else Command.LEFT
                    abs(dy) >= threshold && abs(dy) > abs(dx) * 1.4f -> if (dy > 0) Command.DOWN else Command.UP
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
        if (name == "Open_Palm" && now - since > 1200) { since = now; originX = x; originY = y }
        if (command != null) { lastAction = now; lastMotion = now; latched = true; firedGesture = name }
        return command
    }
}
