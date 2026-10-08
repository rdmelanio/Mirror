package com.mirror.app.phone.roster

import java.net.URI

/** Sanitization happens before a URL, provider error or console message reaches private storage. */
object EcrewLogRedaction {
    private val identifier = Regex("\\d{6,}")
    private val longToken = Regex("[A-Za-z0-9]{32,}")
    private val urls = Regex("(?i)(?:https?|blob):[^\\s<>\\\"']+")
    fun path(raw: String): String = raw.substringBefore('?').substringBefore('#').split('/').joinToString("/") {
        val decoded = runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
        if (decoded.length > 24 || decoded.count { ch -> ch.isDigit() } >= 6 || decoded.any { ch -> ch == '\n' || ch == '\r' || ch in "?#=;&\\" }) "*" else decoded
    }
    fun address(url: String?): String = runCatching {
        val u = URI(url ?: "")
        val host = u.host ?: return@runCatching if (url?.startsWith("/") == true) path(url) else "[non-network URL]"
        host.lowercase() + path(u.rawPath.orEmpty()).ifEmpty { "/" }
    }.getOrDefault("[invalid URL]")
    fun isLogin(url: String?) = runCatching { URI(url ?: "").path.orEmpty().contains("/Login", true) }.getOrDefault(false)
    fun console(raw: String, login: Boolean = false): String {
        if (login) return "[Login console message omitted]"
        // Browser errors are useful. Arbitrary application payloads can contain crew data, so omit
        // them instead of guessing whether a short name, password or booking reference is personal.
        val technical = Regex("^(?:Uncaught|TypeError:|ReferenceError:|SyntaxError:|RangeError:|Failed to load resource|Access to (?:XMLHttpRequest|fetch)|Mixed Content:|net::ERR_|Error: net::|Blocked |Refused to |The Content Security Policy|A cookie |Cookie |WebSocket connection|[\\[ ]*Violation)", RegexOption.IGNORE_CASE)
        if (!technical.containsMatchIn(raw.trim())) return "[page console message omitted]"
        var value = urls.replace(raw) { address(it.value) }
        value = Regex("[?&][^\\s\\\"'<>]+").replace(value, "…")
        value = Regex("(?i)\\b(?:authorization|cookie|password|passwd|token|secret|session|booking|crewId)\\s*[:=]\\s*[^,;\\s]+").replace(value, "[credential omitted]")
        value = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").replace(value, "…")
        value = Regex("(['\\\"])[^'\\\"]*['\\\"]").replace(value, "'…'")
        value = identifier.replace(value, "…")
        value = longToken.replace(value, "…")
        // Preserve standard exception kinds, omit application-specific exception payloads.
        if (Regex("^(?:Uncaught\\s+)?(?:TypeError|ReferenceError|SyntaxError|RangeError|Error):", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())) {
            val kind = value.substringBefore(':')
            val reason = value.substringAfter(':').trim()
            value = kind + ": " + when {
                reason.startsWith("Cannot read", true) -> "Cannot read properties [details omitted]"
                reason.startsWith("Cannot set", true) -> "Cannot set properties [details omitted]"
                reason.contains("is not a function", true) -> "[symbol] is not a function"
                reason.contains("is not defined", true) -> "[symbol] is not defined"
                reason.startsWith("Unexpected", true) -> "Unexpected token [details omitted]"
                else -> "[details omitted]"
            }
        }
        if (!Regex("^(?:Uncaught\\s+)?(?:TypeError|ReferenceError|SyntaxError|RangeError|Error):", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())) {
            // Browser-produced wording is reduced to its fixed diagnostic category. This prevents
            // application console calls that happen to start with an error prefix leaking a payload.
            val category = when {
                raw.startsWith("Failed to load resource", true) -> "Failed to load resource"
                raw.startsWith("Access to", true) -> "Access to XMLHttpRequest/fetch; check CORS policy"
                raw.startsWith("Mixed Content", true) -> "Mixed Content"
                raw.startsWith("Refused", true) -> "Refused by browser policy"
                raw.startsWith("Blocked", true) -> "Blocked by browser policy"
                raw.startsWith("WebSocket", true) -> "WebSocket connection error"
                raw.contains("cookie", true) -> "Cookie policy warning"
                raw.contains("Content Security Policy", true) -> "Content Security Policy warning"
                raw.contains("Violation", true) -> "Browser performance violation"
                else -> "Browser script/network error [details omitted]"
            }
            val errors = Regex("net::ERR_[A-Z_]+|status of [0-9]{3}").findAll(raw).map { it.value }.distinct().joinToString(" ")
            val addresses = urls.findAll(raw).map { address(it.value) }.distinct().take(3).joinToString(" ")
            value = listOf(category, errors, addresses, if (longToken.containsMatchIn(raw)) "…" else "").filter { it.isNotEmpty() }.joinToString(" ")
        }
        return value.replace('\n', ' ').replace('\r', ' ').take(200)
    }
    fun error(raw: String): String {
        // WebView supplies these standard network descriptions; unknown provider text is omitted.
        val known = listOf("net::ERR_", "The webpage", "Connection", "Unable to", "Failed to", "SSL", "Certificate", "Too many redirects", "Name not resolved", "Internet disconnected")
        if (known.none { raw.startsWith(it, true) }) return "[provider description omitted]"
        return longToken.replace(identifier.replace(urls.replace(raw) { address(it.value) }, "…"), "…")
            .replace('\n', ' ').replace('\r', ' ').take(200)
    }
}

class EcrewRequestLogWindow {
    private val seen = linkedMapOf<String, Long>()
    @Synchronized fun record(method: String, address: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value >= 60_000 || now < it.value }
        val key = "$method $address"
        if (seen.containsKey(key)) return false
        if (seen.size >= 2000) seen.remove(seen.keys.first())
        seen[key] = now; return true
    }
}
class EcrewLoopDetector {
    private val visits = mutableListOf<Pair<String, Long>>()
    private var reported = false
    fun navigation(host: String?, path: String, now: Long): Boolean {
        if (reported) return false
        visits.removeAll { now - it.second > 60_000 || now < it.second }
        val target = if (host.equals("ecrew.cebupacificair.com", true)) path.trimEnd('/').lowercase() else ""
        if (target !in listOf("/ecrew", "/ecrew/dashboard")) { visits.clear(); return false }
        if (visits.lastOrNull()?.first == target) return false // History/finish duplicates aren't redirects.
        visits += target to now
        // Three alternating transitions; finish/history callbacks do not add visits.
        if (visits.size >= 4) { reported = true; return true }
        return false
    }
}
/** No pending flag, queue or navigation callback exists. An ineligible tap is discarded. */
class EcrewLogoutGate {
    fun tap(eligible: Boolean, click: () -> Unit): Boolean {
        if (!eligible) return false
        click(); return true
    }
}
