package com.mirror.app.phone.roster

import java.io.InputStream
import java.net.URI

/** Only fixed commands cross the native port. Time and responses are injectable for JVM tests. */
class EcrewAutomation(private val now: () -> Long, private val send: (String, Int) -> Unit,
    private val pending: (Boolean) -> Unit, private val finished: (Boolean, String) -> Unit,
    private val changed: (String) -> Unit = {}, private val timedOut: (String) -> Unit = {}) {
    enum class Step { IDLE, OPEN_MY_SCHEDULE, CHECK_PENDING_CHANGES, CLICK_PRINT, WAIT_PREVIEW, OPEN_EXPORT, CHOOSE_PDF, CAPTURE_PDF, EXIT, PARSE, NEXT_PERIOD }
    var step = Step.IDLE; private set
    var request = 0; private set
    var active = false; private set
    val captureAllowed get() = active && step in listOf(Step.CLICK_PRINT, Step.WAIT_PREVIEW, Step.OPEN_EXPORT, Step.CHOOSE_PDF, Step.CAPTURE_PDF)
    var paused = false; private set
    private var pausedAt = 0L
    private var pauseDuration = 0L
    private fun clock() = (if (paused) pausedAt else now()) - pauseDuration
    fun pause() { if (!paused) { pausedAt = now(); paused = true } }
    fun resume() { if (paused) { pauseDuration += now() - pausedAt; paused = false } }
    private var started = 0L
    private var since = 0L
    private var sentAt = Long.MIN_VALUE
    private var next = false
    private var captured = false
    private var parsed = false
    private var captureStart = 0
    private var awaitingParse = false
    private var nextAfterExit = false
    fun start(): Boolean {
        if (active) return false
        active = true; started = clock(); next = false; captured = false; awaitingParse = false
        move(Step.OPEN_MY_SCHEDULE); return true
    }
    fun manualPrint() {
        if (active && step == Step.PARSE) return
        active = true; started = clock(); next = true; captured = false; awaitingParse = false
        move(Step.CAPTURE_PDF); captureStart = request
    }
    private fun move(value: Step) { step = value; since = clock(); sentAt = Long.MIN_VALUE; request++; if (value == Step.CLICK_PRINT) captureStart = request; changed(value.name) }
    fun acceptsPdf(id: Int) = captureAllowed && id in captureStart..request
    fun tick() {
        if (!active || paused) return
        if (clock() - started >= 120_000 || clock() - since >= 25_000) { timedOut(step.name); stop(false, "timeout; manual Print or import available"); return }
        if (sentAt != Long.MIN_VALUE && clock() - sentAt < 1500) return
        val command = when (step) {
            Step.OPEN_MY_SCHEDULE -> "openSchedule"
            Step.CHECK_PENDING_CHANGES -> "checkPending"
            Step.CLICK_PRINT -> "print"
            Step.WAIT_PREVIEW -> "waitPreview"
            Step.OPEN_EXPORT -> "openExport"
            Step.CHOOSE_PDF -> "choosePdf"
            Step.CAPTURE_PDF -> "capture"
            Step.NEXT_PERIOD -> "nextPeriod"
            Step.EXIT -> "exit"
            else -> return
        }
        sentAt = clock(); send(command, request)
    }
    fun response(id: Int, result: String, hasPending: Boolean = false) {
        if (!active || paused || id != request) return
        if (result == "terminated" || result == "login") { stop(false, "session expired"); return }
        when (step) {
            Step.OPEN_MY_SCHEDULE -> if (result == "schedule") move(Step.CHECK_PENDING_CHANGES)
            Step.CHECK_PENDING_CHANGES -> if (result in listOf("pending", "clear")) { pending(result == "pending"); move(Step.CLICK_PRINT) }
            Step.CLICK_PRINT -> if (result == "printed") move(Step.WAIT_PREVIEW)
            Step.WAIT_PREVIEW -> if (result == "preview") move(Step.OPEN_EXPORT)
            Step.OPEN_EXPORT -> if (result == "export") move(Step.CHOOSE_PDF)
            Step.CHOOSE_PDF -> if (result == "pdf") move(Step.CAPTURE_PDF)
            Step.CAPTURE_PDF -> if (hasPending) pending(true)
            Step.NEXT_PERIOD -> if (result == "next") move(Step.CLICK_PRINT)
            Step.EXIT -> if (result == "exit") {
                if (awaitingParse) { awaitingParse = false; move(Step.PARSE) }
                else if (nextAfterExit) { next = true; move(Step.NEXT_PERIOD) }
                else stop(parsed, if (parsed) "success" else "parse failed")
            }
            else -> Unit
        }
    }
    fun pdf(): Boolean { if (!captureAllowed) return false; awaitingParse = true; move(Step.EXIT); return true }
    fun parsed(success: Boolean, fetchNextPeriod: Boolean) {
        if (!active || step != Step.PARSE) return
        parsed = success; captured = captured || success
        nextAfterExit = success && !next && fetchNextPeriod
        if (nextAfterExit) { next = true; move(Step.NEXT_PERIOD) } else stop(parsed, if (parsed) "success" else "parse failed")
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
    fun exportPage(url: String?, allowBlank: Boolean = false) = page(url) || url?.startsWith("blob:https://ecrew.cebupacificair.com/") == true ||
        (allowBlank && url in listOf("", "about:blank"))
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
