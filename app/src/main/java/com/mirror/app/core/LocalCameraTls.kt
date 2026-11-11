package com.mirror.app.core

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Explicitly provisioned camera certificate pin, separate from Android TV Remote v2.
 * This does not discover or trust first-use identities. A missing pin retains platform CA validation. */
object LocalCameraTls {
    fun fingerprint(certificate:X509Certificate):String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    fun apply(connection:HttpsURLConnection, expectedFingerprint:String) {
        val expected=expectedFingerprint.replace(":","").lowercase(java.util.Locale.ROOT)
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid camera certificate fingerprint" }
        fun matches(c:X509Certificate)=MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),fingerprint(c).toByteArray(Charsets.US_ASCII))
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) { throw CertificateException("Client trust not supported") }
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                val leaf=chain.firstOrNull() ?: throw CertificateException("Missing camera certificate")
                leaf.checkValidity()
                if(!matches(leaf)) throw CertificateException("Camera identity changed")
            }
        }
        connection.sslSocketFactory=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }.socketFactory
        // The explicitly provisioned certificate is the identity, including when a LAN IP changes.
        connection.hostnameVerifier=javax.net.ssl.HostnameVerifier { _, session ->
            runCatching { matches(session.peerCertificates.first() as X509Certificate) }.getOrDefault(false)
        }
        connection.instanceFollowRedirects=false
    }
}
