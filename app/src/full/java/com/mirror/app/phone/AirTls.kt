package com.mirror.app.phone

import java.security.KeyStore
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Same client identity on every connection; TV trust is pinned after code-authenticated pairing. */
internal object AirTls {
    fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun context(key: PrivateKey, certificate: X509Certificate, pin: String?): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("air", key, CharArray(0), arrayOf(certificate)) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { error("Client trust unavailable") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                require(chain.isNotEmpty())
                if (pin != null) check(fingerprint(chain[0]) == pin) { "TV certificate changed; pair again" }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), null) }
    }
}
