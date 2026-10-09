package com.mirror.app.phone.roster

/** Independent of robot deadlines: late trusted export taps remain eligible. */
class EcrewExportWindow(private val now: () -> Long, private val log: (String) -> Unit = {}) {
    private var until = 0L
    val armed: Boolean get() {
        if (until == 0L) return false
        if (now() >= until) { until = 0; log("expired"); return false }
        return true
    }
    fun arm(source: String) {
        val extended = armed
        until = now() + 180_000
        log("${if (extended) "extended" else "armed"} by $source (180 s)")
    }
    fun clear() { until = 0 }
}
