package com.mirror.app.phone.roster

/** One command round can be answered by any trusted frame. Late rounds never advance a step. */
class EcrewFrames<T> {
    data class Frame(val top: Boolean, val path: String, var linked: Boolean = false, var pending: Boolean = false, var terminated: Boolean = false, var recording: Boolean = false)
    val frames = linkedMapOf<T, Frame>()
    private var request = -1
    private var round = 0
    private var answered = false
    private val waiting = mutableSetOf<T>()
    fun hello(port: T, top: Boolean, path: String): Boolean {
        if (!EcrewPortPolicy.page("https://ecrew.cebupacificair.com$path") || path.contains('?') || path.contains('#') || (port !in frames && frames.size >= 8)) return false
        frames[port] = Frame(top, path); return true
    }
    fun disconnect(port: T) { frames.remove(port); waiting.remove(port) }
    fun clear() { frames.clear(); waiting.clear(); request = -1; answered = false }
    fun begin(id: Int): Int { request = id; round++; answered = false; waiting.clear(); waiting.addAll(frames.keys); return round }
    fun result(port: T, id: Int, responseRound: Int, result: String): String? {
        if (id != request || responseRound != round || answered || port !in frames) return null
        if (result != "wait") { answered = true; return result }
        waiting.remove(port)
        return if (waiting.isEmpty()) { answered = true; "wait" } else null
    }
    val pending get() = frames.values.any { it.pending }
    val linked get() = frames.values.any { it.linked }
    val terminated get() = frames.values.any { it.terminated }
    fun top() = frames.entries.firstOrNull { it.value.top }?.key
}
object EcrewSnapshot {
    fun text(value: String) = value.replace(Regex("\\d{5,}"), "…").take(30)
    fun path(value: String) = value.substringBefore('?').substringBefore('#').replace(Regex("\\d{5,}"), "…").take(160)
    fun icon(value: String) = value.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,79}")) }
}
