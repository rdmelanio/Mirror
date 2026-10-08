package com.mirror.app.phone.roster

/** Policies shared by the browser and JVM tests; no account or page contents are retained. */
object EcrewPageMatcher {
    const val SCHEDULE_PREFIX = "My Schedule"
    fun schedule(text: String) = text.trim().startsWith(SCHEDULE_PREFIX, ignoreCase = true)
    fun signedIn(visibleTexts: List<String>, sidebarCalendar: Boolean, avatar: Boolean, topBar: Boolean) =
        visibleTexts.any(::schedule) || sidebarCalendar || (avatar && topBar)
    fun scheduleTarget(visibleTexts: List<String>, sidebarCalendar: Boolean): String? =
        if (visibleTexts.any(::schedule)) "text" else if (sidebarCalendar) "sidebar" else null
    fun terminated(text: String) = text.contains("Another active session", ignoreCase = true)
    // This predicate is also used by the in-document matcher, including its manual Print observer.
    fun javascript() = """
        const scheduleText=t=>t.trim().toLowerCase().startsWith('${SCHEDULE_PREFIX.lowercase()}');
        const terminatedText=t=>t.toLowerCase().includes('another active session');
    """.trimIndent()
}
object EcrewBrowserIdentity {
    fun userAgent(default: String) = default.replace("; wv", "").replace("Version/4.0 ", "")
}
class EcrewBrowserLifetime {
    var created = false; private set
    var started = false; private set
    var destroyed = false; private set
    private var loaded = false
    fun create(): Boolean {
        if (created || destroyed) return false
        created = true; return true
    }
    fun start() { if (created && !destroyed) started = true }
    fun stop() { started = false }
    fun sawEcrewPage() { loaded = true }
    fun open(): Boolean {
        if (!created || destroyed || loaded) return false
        loaded = true; return true
    }
    fun destroy(): Boolean {
        if (!created || destroyed) return false
        destroyed = true; started = false; return true
    }
}
class EcrewLogoutOrder {
    enum class State { IDLE, WAITING, CLEARING, DONE, TIMED_OUT }
    var state = State.IDLE; private set
    private var deadline = 0L
    fun begin(now: Long) { state = State.WAITING; deadline = now + 10_000 }
    fun loginShown(isLogin: Boolean, now: Long): Boolean {
        if (state != State.WAITING || !isLogin || now > deadline) return false
        state = State.CLEARING; return true
    }
    fun timeout(now: Long): Boolean {
        if (state != State.WAITING || now < deadline) return false
        state = State.TIMED_OUT; return true
    }
    fun complete() { check(state == State.CLEARING); state = State.DONE }
}
