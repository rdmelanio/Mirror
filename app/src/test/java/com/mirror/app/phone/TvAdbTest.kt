package com.mirror.app.phone

import io.github.muntashirakon.adb.AndroidPubkey
import io.github.muntashirakon.adb.PairingAuthCtx
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.Vector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvAdbTest {
    private class Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate: X509Certificate
        init {
            val provider = BouncyCastleProvider(); val name = X500Name("CN=Test ADB"); val now = System.currentTimeMillis()
            certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 60000), Date(now + 86400000), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
        }
    }
    private val identity = Identity()
    private inner class Server : DefaultTlsServer(BcTlsCrypto(SecureRandom())) {
        val signature = SignatureAndHashAlgorithm.getInstance(HashAlgorithm.Intrinsic, SignatureAlgorithm.rsa_pss_rsae_sha256)
        override fun getSupportedVersions() = arrayOf(ProtocolVersion.TLSv13)
        override fun getCredentials(): TlsCredentials = BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context), crypto as BcTlsCrypto,
            PrivateKeyFactory.createKey(identity.pair.private.encoded),
            Certificate(ByteArray(0), arrayOf(CertificateEntry(crypto.createCertificate(identity.certificate.encoded), null))), signature)
        override fun getCertificateRequest() = CertificateRequest(ByteArray(0), Vector<SignatureAndHashAlgorithm>().apply { add(signature); add(SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.rsa)) }, null, null)
        override fun notifyClientCertificate(certificate: Certificate) { assertArrayEquals(identity.certificate.encoded, certificate.getCertificateAt(0).encoded) }
        private var exported: ByteArray? = null
        override fun notifyHandshakeComplete() { super.notifyHandshakeComplete(); exported = context.exportKeyingMaterial("adb-label\u0000", null, 64) }
        fun binding() = exported!!.clone()
    }
    private fun <T> peer(serverWork: (Socket) -> Unit, clientWork: (Socket) -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val result = worker.submit { server.accept().use { socket -> socket.soTimeout = 5000; serverWork(socket) } }
            try {
                val answer = Socket("127.0.0.1", server.localPort).use { it.soTimeout = 5000; clientWork(it) }
                result.get(10, TimeUnit.SECONDS); return answer
            } catch (error: Throwable) {
                val serverError = runCatching { result.get(6, TimeUnit.SECONDS) }.exceptionOrNull()
                if (serverError is java.util.concurrent.ExecutionException) throw AssertionError("Simulated TV failed", serverError.cause)
                throw error
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun localTargetsAndDiscoveryNeverAcceptUnrelatedDevice() {
        assertEquals("192.168.0.231", TvTarget.host(" 192.168.0.231 "))
        for (bad in listOf("example.com", "127.0.0.1", "8.8.8.8", "192.168.0.231; reboot", "192.168.000.1", "192.168.1.999"))
            assertTrue(runCatching { TvTarget.host(bad) }.isFailure)
        assertEquals(40795, TvTarget.port("40795"))
        for (bad in listOf("0", "65536", "x")) assertTrue(runCatching { TvTarget.port(bad) }.isFailure)
        assertTrue(TvTarget.matches("adb-TV-guid-random", "adb-TV-guid"))
        assertFalse(TvTarget.matches("adb-TV-guid2-random", "adb-TV-guid"))
        assertFalse(TvTarget.matches("adb-other-device", "adb-TV-guid")); assertFalse(TvTarget.matches("anything", ""))
    }
    @Test fun packetReaderRejectsCorruptAndOversizedInput() {
        val bytes = ByteArrayOutputStream(); TvAdbWire.write(bytes, "WRTE", 7, 1, "test".toByteArray())
        val packet = TvAdbWire.read(bytes.toByteArray().inputStream()); assertEquals(7, packet.first); assertEquals("test", packet.data.toString(Charsets.UTF_8))
        val corrupt = bytes.toByteArray(); corrupt[20] = 0
        assertTrue(runCatching { TvAdbWire.read(corrupt.inputStream()) }.isFailure)
        val huge = bytes.toByteArray(); ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE)
        assertTrue(runCatching { TvAdbWire.read(huge.inputStream()) }.isFailure)
    }
    @Test fun realTlsPairingBindsCodeAndExchangesAndroidPublicKey() {
        fun read(input: DataInputStream, type: Int): ByteArray {
            assertEquals(1, input.readUnsignedByte()); assertEquals(type, input.readUnsignedByte())
            val size = input.readInt(); assertTrue(size in 1..16384); return ByteArray(size).also(input::readFully)
        }
        fun write(output: DataOutputStream, type: Int, bytes: ByteArray) {
            output.writeByte(1); output.writeByte(type); output.writeInt(bytes.size); output.write(bytes); output.flush()
        }
        val guid = peer({ socket ->
            val tls = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream()); val server = Server(); tls.accept(server)
            val auth = PairingAuthCtx.createBob("123456".toByteArray() + server.binding())!!
            try {
                val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
                val msg = read(input, 0); write(output, 0, auth.msg); assertTrue(auth.initCipher(msg))
                val client = auth.decrypt(read(input, 1))!!; assertEquals(8192, client.size); assertEquals(0, client[0].toInt())
                val public = AndroidPubkey.encodeWithName(identity.pair.public as RSAPublicKey, "Mirror Clock")
                assertArrayEquals(public, client.copyOfRange(1, public.size + 1))
                val remote = ByteArray(8192); remote[0] = 1; "adb-TV-test".toByteArray().copyInto(remote, 1)
                write(output, 1, auth.encrypt(remote)!!)
            } finally { auth.destroy() }
        }) { socket -> TvAdbWire.pair(socket, identity.pair.private, identity.certificate, "123456") }
        assertEquals("adb-TV-test", guid)
    }
    @Test fun wakePrecedesLaunchAndAlreadyRunningResponseSucceeds() {
        val result = peer({ socket ->
            val cnxn = TvAdbWire.read(socket.getInputStream()); assertEquals(TvAdbWire.command("CNXN"), cnxn.command)
            TvAdbWire.write(socket.getOutputStream(), "STLS", 0x01000000, 0)
            assertEquals(TvAdbWire.command("STLS"), TvAdbWire.read(socket.getInputStream()).command)
            val tls = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream()); tls.accept(Server())
            TvAdbWire.write(tls.outputStream, "CNXN", 0x01000001, 1_048_576, "device::\u0000".toByteArray())
            val open = TvAdbWire.read(tls.inputStream); assertEquals("shell:${TvAdbWire.LAUNCH}\u0000", open.data.toString(Charsets.UTF_8))
            assertTrue(TvAdbWire.LAUNCH.startsWith("input keyevent 224; am start -W"))
            TvAdbWire.write(tls.outputStream, "OKAY", 77, 1)
            TvAdbWire.write(tls.outputStream, "WRTE", 77, 1, "Starting: Intent\nWarning: Activity not started, intent has been delivered to currently running top-most instance.".toByteArray())
            assertEquals(TvAdbWire.command("OKAY"), TvAdbWire.read(tls.inputStream).command)
            TvAdbWire.write(tls.outputStream, "CLSE", 77, 1)
            assertEquals(TvAdbWire.command("CLSE"), TvAdbWire.read(tls.inputStream).command)
        }) { TvAdbWire.launch(it, identity.pair.private, identity.certificate) }
        assertEquals("TV awake · Mirror launched", result)
    }
    @Test fun legacyPlaintextConnectionIsRejected() {
        peer({ socket ->
            TvAdbWire.read(socket.getInputStream()); TvAdbWire.write(socket.getOutputStream(), "CNXN", 0x01000001, 4096)
        }) { socket -> assertTrue(runCatching { TvAdbWire.launch(socket, identity.pair.private, identity.certificate) }.isFailure) }
    }
    @Test fun iconSettingsRoundTripAndOldClockChoicesStayIntact() {
        val s = ClockSettings(showUtc = false, sizePercent = 95, tvIcon = true, tvIconSize = 62,
            warningSeconds = 15, positions = mapOf("tv_launch" to ClockPosition(.3f, .8f)))
        assertEquals(s, ClockSettings.parse(s.json()))
        val old = ClockSettings.parse("""{"schema":6,"style":"Stacked","warningSound":"content://custom","sizePercent":100}""")
        assertEquals("Stacked", old.style); assertEquals("content://custom", old.warningSound); assertEquals(100, old.sizePercent)
        assertFalse(old.tvIcon); assertEquals(36, old.tvIconSize)
        assertEquals(24, ClockSettings.parse("""{"tvIconSize":-1}""").tvIconSize)
        assertEquals(96, ClockSettings.parse("""{"tvIconSize":500}""").tvIconSize)
    }
}
