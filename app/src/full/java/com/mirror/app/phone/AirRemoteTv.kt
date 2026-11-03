package com.mirror.app.phone

import android.content.Context
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Remote v2 TLS pairing (6467) and control (6466), independent of ADB. Never replay keys. */
internal object AirRemoteTv {
    @Volatile var status = "Not connected"; private set
    @Volatile var connected = false; private set
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val pairWorker = Executors.newSingleThreadExecutor()
    private val codes = ArrayBlockingQueue<String>(1)
    private val keys = ArrayBlockingQueue<Pair<AirGestures.Command, Long>>(1)
    @Volatile private var wanted = false
    @Volatile private var pairing = false
    @Volatile private var generation = 0
    @Volatile private var socket: SSLSocket? = null
    private var nextConnect = 0L
    private var lastPacket = 0L
    private var session = AirSession()
    private var app: Context? = null
    private var loop: java.util.concurrent.ScheduledFuture<*>? = null
    fun prefs(context: Context) = context.getSharedPreferences("air_remote", Context.MODE_PRIVATE)
    @Synchronized fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
    }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    @Synchronized fun connect(context: Context) {
        init(context); wanted = true
        if (loop == null || loop!!.isCancelled) loop = worker.scheduleWithFixedDelay({
            runCatching { tick() }.onFailure {
                disconnect()
                if (wanted) status = "TV connection: ${it.message ?: "failed"}. Check Wi-Fi / pair again."
                nextConnect = now() + 5000
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun stop() {
        generation++; wanted = false; loop?.cancel(false); loop = null; keys.clear(); disconnect()
        if (!pairing) status = "Disconnected"
    }
    private fun disconnect() { connected = false; val old = socket; socket = null; runCatching { old?.close() }; keys.clear() }
    fun send(command: AirGestures.Command) {
        if (!connected) { status = "TV unavailable · gesture discarded"; return }
        keys.offer(command to now())
    }
    private fun tls(context: Context, host: String, port: Int, pin: String?): SSLSocket {
        val identity = AirCredentials.load(context, create = true)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("air", identity.key, CharArray(0), arrayOf(identity.certificate)) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { error("Client trust unavailable") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                require(chain.isNotEmpty())
                // Bootstrap trust is authenticated by the TV-displayed pairing code before pin persistence.
                if (pin != null) check(fingerprint(chain[0]) == pin) { "TV certificate changed; pair again" }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), null) }
        return (ssl.socketFactory.createSocket() as SSLSocket).also { s ->
            try { s.soTimeout = 8000; s.connect(InetSocketAddress(TvTarget.host(host), port), 4000); s.startHandshake() }
            catch (e: Exception) { s.close(); throw e }
        }
    }
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun tick() {
        if (!wanted || pairing) return
        val context = app ?: return
        var s = socket
        if (s == null) {
            if (now() < nextConnect) return
            nextConnect = now() + 5000
            val p = prefs(context); val pin = p.getString("pin", null) ?: run { status = "Pair TV first"; return }
            status = "Connecting…"
            s = tls(context, p.getString("host", "").orEmpty(), 6466, pin)
            if (!wanted || pairing) { s.close(); return }
            socket = s; lastPacket = now(); session = AirSession(); status = "Negotiating remote…"
        }
        val input = s.inputStream
        s.soTimeout = 100
        val first = try { input.read() } catch (_: java.net.SocketTimeoutException) { -2 }
        if (first == -1) error("TV disconnected")
        if (first >= 0) {
            s.soTimeout = 8000
            val framed = java.io.SequenceInputStream(byteArrayOf(first.toByte()).inputStream(), input)
            val message = AirWire.read(framed); lastPacket = now()
            session.receive(message)?.let { AirWire.write(s.outputStream, it) }
            connected = session.ready
            if (connected) status = "Connected · Android TV Remote"
        }
        check(now() - lastPacket < 30_000) { "TV stopped responding" }
        keys.poll()?.let { (key, at) -> if (connected && now() - at < 500) AirWire.write(s.outputStream, session.key(key)) }
    }
    fun pair(context: Context, address: String) {
        if (pairing) return
        val host = TvTarget.host(address)
        init(context); pairing = true; val attempt = generation; codes.clear(); disconnect(); status = "Requesting pairing code on TV…"
        pairWorker.execute {
            try {
                tls(context.applicationContext, host, 6467, null).use { s ->
                    fun exchange(field: Int, body: ByteArray, expected: Int) {
                        AirWire.write(s.outputStream, AirWire.pairing(field, body))
                        val reply = AirWire.fields(AirWire.read(s.inputStream))
                        check(AirWire.value(reply[2]) == 200 && expected in reply) { "TV rejected pairing (stage $field)" }
                    }
                    exchange(10, AirWire.text(1, "atvremote") + AirWire.text(2, "Mirror Air Remote"), 11)
                    val encoding = AirWire.number(1, 3) + AirWire.number(2, 6)
                    exchange(20, AirWire.bytes(1, encoding) + AirWire.number(3, 1), 20)
                    exchange(30, AirWire.bytes(1, encoding) + AirWire.number(2, 1), 31)
                    status = "Enter the six-character code displayed on TV"
                    val code = codes.poll(60, TimeUnit.SECONDS) ?: error("Pairing code timed out")
                    val server = s.session.peerCertificates[0] as X509Certificate
                    val client = AirCredentials.load(context).certificate
                    fun unsigned(v: java.math.BigInteger): ByteArray = v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                    fun publicBytes(c: X509Certificate): ByteArray { val k = c.publicKey as RSAPublicKey; return unsigned(k.modulus) + unsigned(k.publicExponent) }
                    val suffix = byteArrayOf(code.substring(2, 4).toInt(16).toByte(), code.substring(4, 6).toInt(16).toByte())
                    val secret = MessageDigest.getInstance("SHA-256").digest(publicBytes(client) + publicBytes(server) + suffix)
                    check((secret[0].toInt() and 255) == code.substring(0, 2).toInt(16)) { "Incorrect TV code; start pairing again" }
                    exchange(40, AirWire.bytes(1, secret), 41)
                    prefs(context).edit().putString("host", host).putString("pin", fingerprint(server)).apply()
                    nextConnect = 0
                    if (attempt == generation) { status = "Paired · connecting…"; connect(context.applicationContext) } else status = "Paired · disconnected"
                }
            } catch (e: Exception) { status = "Pairing failed: ${e.message}. Check Android TV Remote Service on the TV." }
            finally { pairing = false }
        }
    }
    fun code(raw: String) { val code = raw.trim().uppercase(); require(code.matches(Regex("[0-9A-F]{6}"))) { "Enter six hexadecimal characters from TV" }; check(pairing) { "Start pairing first" }; codes.offer(code) }
}
