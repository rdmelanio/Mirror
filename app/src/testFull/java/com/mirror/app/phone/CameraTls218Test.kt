package com.mirror.app.phone

import com.mirror.app.core.LocalCameraTls
import com.mirror.app.tv.SourceAuth
import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.*
import java.math.BigInteger
import java.util.Date
import java.net.*
import javax.net.ssl.*

class CameraTls218Test {
    @Test fun explicitlyPinnedHttpsServesExistingTokensAndRejectsUnknownIdentity() {
        val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val provider=BouncyCastleProvider();val now=System.currentTimeMillis();val name=X500Name("CN=local-camera-test")
        val cert=JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(name,BigInteger.ONE,Date(now-1000),Date(now+86400000),name,key.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(key.private)))
        val store=KeyStore.getInstance("PKCS12").apply { load(null);setKeyEntry("camera",key.private,CharArray(0),arrayOf(cert)) }
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,CharArray(0)) }
        val tls=SSLContext.getInstance("TLS").apply { init(keys.keyManagers,null,null) }
        val authority=PairingAuthority();val token=authority.pair(authority.showCode()!!,"id","TV").token!!
        val server=MjpegServer("Camera",StreamStats(),authority,InetAddress.getByName("127.0.0.1"),allowAddress={it.isLoopbackAddress},listenPort=0,socketFactory=tls.serverSocketFactory)
        server.start()
        try {
            fun request(pin:String):Int {
                val http=URL("https://127.0.0.1:${server.port}/status").openConnection() as HttpsURLConnection
                http.connectTimeout=3000;http.readTimeout=3000
                return try { SourceAuth(token,trustedFingerprint=pin).apply(http);http.responseCode } finally { http.disconnect() }
            }
            assertEquals(200,request(LocalCameraTls.fingerprint(cert)))
            try { request("00".repeat(32));fail("unknown identity trusted") } catch (_:SSLHandshakeException) {}
            try { SourceAuth(token,trustedFingerprint=LocalCameraTls.fingerprint(cert)).apply(URL("http://127.0.0.1/").openConnection() as HttpURLConnection);fail("HTTP downgrade") } catch (_:IllegalArgumentException) {}
        } finally { server.close() }
    }
}
