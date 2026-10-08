package com.mirror.app.phone.roster

import java.io.InputStream
import java.net.URI

/** Only fixed commands cross the native port. Time and responses are injectable for JVM tests. */
class EcrewAutomation(private val now: () -> Long, private val send: (String, Int) -> Unit,
    private val pending: (Boolean) -> Unit, private val finished: (Boolean, String) -> Unit,
    private val changed: (String) -> Unit = {}) {
    enum class Step { IDLE, OPEN_MY_SCHEDULE, CHECK_PENDING_CHANGES, CLICK_PRINT, CAPTURE_PDF, PARSE, EXIT, NEXT_PERIOD }
    var step = Step.IDLE; private set
    var request = 0; private set
    var active = false; private set
    val captureAllowed get() = active && step in listOf(Step.CLICK_PRINT, Step.CAPTURE_PDF)
    private var started = 0L
    private var since = 0L
    private var sentAt = Long.MIN_VALUE
    private var next = false
    private var captured = false
    private var parsed = false
    private var nextAfterExit = false
    fun start(): Boolean {
        if (active) return false
        active = true; started = now(); next = false; captured = false
        move(Step.OPEN_MY_SCHEDULE); return true
    }
    fun manualPrint() {
        if (active && step == Step.PARSE) return
        active = true; started = now(); next = true; captured = false
        move(Step.CAPTURE_PDF)
    }
    private fun move(value: Step) { step = value; since = now(); sentAt = Long.MIN_VALUE; request++; changed(value.name) }
    fun tick() {
        if (!active) return
        if (now() - started >= 90_000 || now() - since >= 25_000) { stop(false, "timeout; manual Print or import available"); return }
        if (sentAt != Long.MIN_VALUE && now() - sentAt < 1500) return
        val command = when (step) {
            Step.OPEN_MY_SCHEDULE -> "openSchedule"
            Step.CHECK_PENDING_CHANGES -> "checkPending"
            Step.CLICK_PRINT -> "print"
            Step.CAPTURE_PDF -> "capture"
            Step.NEXT_PERIOD -> "nextPeriod"
            Step.EXIT -> "exit"
            else -> return
        }
        sentAt = now(); send(command, request)
    }
    fun response(id: Int, result: String, hasPending: Boolean = false) {
        if (!active || id != request) return
        if (result == "terminated" || result == "login") { stop(false, "session expired"); return }
        when (step) {
            Step.OPEN_MY_SCHEDULE -> if (result == "schedule") move(Step.CHECK_PENDING_CHANGES)
            Step.CHECK_PENDING_CHANGES -> if (result in listOf("pending", "clear")) { pending(result == "pending"); move(Step.CLICK_PRINT) }
            Step.CLICK_PRINT -> if (result == "printed") move(Step.CAPTURE_PDF)
            Step.CAPTURE_PDF -> if (hasPending) pending(true)
            Step.NEXT_PERIOD -> if (result == "next") move(Step.CLICK_PRINT)
            Step.EXIT -> if (result == "exit") {
                if (nextAfterExit) { next = true; move(Step.NEXT_PERIOD) }
                else stop(parsed, if (parsed) "success" else "parse failed")
            }
            else -> Unit
        }
    }
    fun pdf(): Boolean { if (!captureAllowed) return false; move(Step.PARSE); return true }
    fun parsed(success: Boolean, fetchNextPeriod: Boolean) {
        if (!active || step != Step.PARSE) return
        parsed = success; captured = captured || success
        nextAfterExit = success && !next && fetchNextPeriod
        move(Step.EXIT)
    }
    fun stop(success: Boolean = false, reason: String = "stopped") {
        val wasActive = active; active = false; step = Step.IDLE; request++
        if (wasActive) finished(success || captured, reason)
    }
}
object EcrewPortPolicy {
    fun page(url: String?): Boolean = runCatching {
        val u = URI(url ?: return false)
        u.scheme == "https" && u.host == "ecrew.cebupacificair.com" && u.port in listOf(-1, 443) &&
            u.rawUserInfo == null && u.path.startsWith("/eCrew/", true) && !u.path.contains("/Login", true)
    }.getOrDefault(false)
    fun clickAllowed(text: String) = !text.trim().startsWith("Confirm all changes", true)
}
object EcrewPdfBytes {
    const val LIMIT = 20 * 1024 * 1024
    fun valid(bytes: ByteArray) = bytes.size in 4..LIMIT && bytes[0] == 37.toByte() && bytes[1] == 80.toByte() && bytes[2] == 68.toByte() && bytes[3] == 70.toByte()
    fun read(body: InputStream?): ByteArray? = body?.use {
        // Validate magic before buffering an untrusted response body.
        val prefix = ByteArray(4)
        var offset = 0
        while (offset < 4) { val n = it.read(prefix, offset, 4 - offset); if (n < 0) return null; offset += n }
        if (!valid(prefix)) return null
        val out = java.io.ByteArrayOutputStream(); out.write(prefix)
        val buffer = ByteArray(8192)
        while (true) {
            val n = it.read(buffer); if (n < 0) break
            if (out.size() + n > LIMIT) return null
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
}
