package com.mirror.app.phone.roster

import java.net.URI

object EcrewProbePolicy {
    fun dashboard(url: String?) = runCatching {
        val u = URI(url ?: "")
        u.scheme == "https" && u.host == "ecrew.cebupacificair.com" && (u.port == -1 || u.port == 443) &&
            u.path.trimEnd('/').lowercase().let { it == "/ecrew/dashboard" || it.startsWith("/ecrew/dashboard/") }
    }.getOrDefault(false)
    fun allowed(explicitTap: Boolean, url: String?) = explicitTap && dashboard(url)
    fun mask(value: String?) = when {
        value == null -> "[absent]"
        value.length <= 6 -> "…"
        else -> value.take(4) + "…" + value.takeLast(2)
    }
    fun name(value: String) = if (Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$").matches(value) && !Regex("\\d{6,}|[A-Za-z0-9]{32,}").containsMatchIn(value)) value else "[name omitted]"
    private val words = setOf("html", "head", "body", "title", "meta", "div", "p", "span", "input", "script", "style", "form", "type", "name", "content", "class", "id", "error", "status", "code", "message", "bad", "request", "invalid", "verification", "token", "session", "expired", "terminated", "another", "active", "currently", "open", "under", "your", "account", "this", "has", "now", "been", "unauthorized", "forbidden", "null", "true", "false")
    fun body(raw: String): String {
        val sample = raw.take(300).replace(Regex("[A-Za-z0-9]{20,}"), "…")
            .replace(Regex("(['\"])[^'\"]*['\"]"), "\"…\"")
        // Response snippets may be roster HTML/JSON. Keep structure and fixed diagnostic words,
        // but omit arbitrary text, attribute/string values, names, IDs and booking references.
        return Regex("[\\p{L}\\p{N}_@.+-]+").replace(sample) { m ->
            if (m.value.lowercase() in words) m.value else "…"
        }.take(300).replace('\n', ' ').replace('\r', ' ')
    }
}
