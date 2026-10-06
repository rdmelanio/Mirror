package com.mirror.app.phone

/** Restrict the remote to an explicitly configured LAN address and the paired mDNS identity. */
internal object TvTarget {
    fun host(raw: String): String {
        val text = raw.trim(); val pieces = text.split('.')
        require(pieces.size == 4 && pieces.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "Enter the TV's local IPv4 address." }
        val n = pieces.map(String::toInt)
        require(n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31) || (n[0] == 169 && n[1] == 254)) { "Use the TV's private LAN address." }
        return text
    }
    fun port(raw: String): Int = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Enter a port from 1 to 65535.")
    fun matches(service: String, guid: String): Boolean = guid.isNotEmpty() &&
        (service == guid || service.startsWith("$guid-") || service.startsWith("adb-$guid-"))
}
