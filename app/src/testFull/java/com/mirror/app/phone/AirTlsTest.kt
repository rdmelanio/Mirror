package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.*
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class AirTlsTest {
    private data class Identity(val key:PrivateKey,val cert:X509Certificate)
    private fun identity(name:String):Identity {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val dn=X500Name("CN=$name");val provider=BouncyCastleProvider();val now=System.currentTimeMillis()
        val cert=JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(dn,BigInteger.valueOf(now),Date(now-1000),Date(now+86400000),dn,pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
        return Identity(pair.private,cert)
    }
    private fun serverContext(server:Identity,client:Identity):SSLContext {
        val store=KeyStore.getInstance("PKCS12").apply { load(null);setKeyEntry("server",server.key,CharArray(0),arrayOf(server.cert)) }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,CharArray(0)) }
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkServerTrusted(chain:Array<X509Certificate>,auth:String) { error("server") }
            override fun checkClientTrusted(chain:Array<X509Certificate>,auth:String) { check(AirTls.fingerprint(chain[0])==AirTls.fingerprint(client.cert)) }
        }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers,arrayOf(trust),null) }
    }
    @Test fun savedIdentityAuthenticatesAgainAfterDisconnectWithoutPairing() {
        val client=identity("saved-Mirror");val tv=identity("paired-TV");val executor=Executors.newSingleThreadExecutor()
        (serverContext(tv,client).serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { listener ->
            listener.needClientAuth=true;listener.soTimeout=5000
            val server=executor.submit<Boolean> {
                repeat(2) { (listener.accept() as SSLSocket).use { s ->
                    s.soTimeout=5000;s.startHandshake()
                    assertEquals(AirTls.fingerprint(client.cert),AirTls.fingerprint(s.session.peerCertificates[0] as X509Certificate))
                    AirWire.write(s.outputStream,byteArrayOf(10,2,8,35));AirWire.read(s.inputStream)
                    AirWire.write(s.outputStream,byteArrayOf(18,2,8,35));AirWire.read(s.inputStream)
                    AirWire.write(s.outputStream,byteArrayOf(0xc2.toByte(),2,2,8,1))
                    assertArrayEquals(byteArrayOf(82,4,8,22,16,3),AirWire.read(s.inputStream))
                } };true
            }
            try {
                repeat(2) {
                    val tls=AirTls.context(client.key,client.cert,AirTls.fingerprint(tv.cert))
                    (tls.socketFactory.createSocket("127.0.0.1",listener.localPort) as SSLSocket).use { s ->
                        s.soTimeout=5000;s.startHandshake();val session=AirSession()
                        repeat(3) { session.receive(AirWire.read(s.inputStream))?.let { AirWire.write(s.outputStream,it) } }
                        assertTrue(session.ready);AirWire.write(s.outputStream,session.key(AirGestures.Command.RIGHT))
                    }
                }
                assertTrue(server.get(10,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun changedTvCertificateIsRejected() {
        val client=identity("Mirror");val tv=identity("different-TV");val executor=Executors.newSingleThreadExecutor()
        (serverContext(tv,client).serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { listener ->
            listener.needClientAuth=true;listener.soTimeout=5000
            val server=executor.submit { runCatching { (listener.accept() as SSLSocket).use { it.soTimeout=5000;it.startHandshake() } } }
            try {
                val tls=AirTls.context(client.key,client.cert,"00".repeat(32))
                try { (tls.socketFactory.createSocket("127.0.0.1",listener.localPort) as SSLSocket).use { it.soTimeout=5000;it.startHandshake() };fail("pin bypassed") }
                catch(_:SSLException) { }
                server.get(10,TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
