package com.mirror.app.core

import java.net.URI

object StreamAddress {
    fun normalize(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter the phone's Wi-Fi address" }
        val uri = URI(if (value.contains("://")) value else "http://$value")
        require(uri.scheme == "http" || uri.scheme == "https") { "Use an http:// or https:// address" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Enter a valid camera address" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port" }
        val path = if (uri.path.isNullOrEmpty() || uri.path == "/") "/video" else uri.path
        return URI(uri.scheme, null, uri.host, uri.port, path, uri.query, null).toASCIIString()
    }
    fun forHost(host: String, port: Int): String = normalize("http://${if (host.contains(':')) "[$host]" else host}:$port/video")
}
