package com.mirror.app.phone

import android.content.Context
import java.net.InetSocketAddress
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
    @Volatile var onSent: ((AirGestures.Command)->Unit)?=null
    private val keys = ArrayBlockingQueue<Pair<AirGestures.Command, Long>>(1)
    @Volatile private var wanted = false
    @Volatile private var pairing = false
    @Volatile private var generation = 0
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var nextConnect = 0L
    @Volatile private var retryRequested = false
    @Volatile var lastCommand = "No command sent"; private set
    @Volatile var lastFailure = ""; private set
    private var lastPacket = 0L
    private var session = AirSession()
    private val candidates=java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var discovery:AirTargetDiscovery?=null
    private var app: Context? = null
    private var loop: java.util.concurrent.ScheduledFuture<*>? = null
    fun prefs(context: Context) = context.getSharedPreferences("air_remote", Context.MODE_PRIVATE)
    @Synchronized fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        status = if (prefs(context).contains("pin")) "Paired · disconnected" else "Not paired"
        discovery=AirTargetDiscovery(context.applicationContext) { host -> if(wanted && candidates.size<4 && host !in candidates) candidates.offer(host) }
    }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    @Synchronized fun connect(context: Context) {
        init(context); wanted = true
        if (loop == null || loop!!.isCancelled) loop = worker.scheduleWithFixedDelay({
            runCatching { tick() }.onFailure {
                disconnect()
                if (wanted) { lastFailure = "${it.javaClass.simpleName}: ${it.message ?: "connection failed"}"; status = "Failed · $lastFailure · retrying in 5 seconds" }
                nextConnect = now() + 5000
                if(wanted) discovery?.scan()
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun stop() {
        generation++; wanted = false;discovery?.stop();candidates.clear(); loop?.cancel(false); loop = null; disconnect()
        if (!pairing) status = "Disconnected"
    }
    fun retry(context: Context, address: String) {
        init(context)
        check(prefs(context).contains("pin")) { "Not paired: pair this TV once first" }
        val host = TvTarget.host(address)
        // Keep the saved identity/pin. A changed address must authenticate as the same paired TV.
        candidates.clear(); candidates.offer(host); nextConnect = 0; retryRequested = true
        connect(context)
    }
    private fun disconnect() { connected = false;
        if (keys.isNotEmpty()) lastCommand = "${keys.peek()?.first} · discarded: connection closed"
        val old = socket; socket = null; runCatching { old?.close() }; keys.clear()
    }
    @Synchronized fun send(command: AirGestures.Command): String {
        val queued = "$command · queued"
        // Publish queued before offer: the worker may transmit immediately after offer succeeds.
        lastCommand = queued
        if (!connected) lastCommand = "$command · discarded: remote session not ready"
        else if (keys.offer(command to now())) return queued
        else lastCommand = "$command · discarded: command queue busy"
        return lastCommand
    }
    private fun tls(context: Context, host: String, port: Int, pin: String?): SSLSocket {
        val identity = AirCredentials.load(context, create = pin == null)
        val ssl = AirTls.context(identity.key, identity.certificate, pin)
        return (ssl.socketFactory.createSocket() as SSLSocket).also { s ->
            try { s.soTimeout = 8000; s.connect(InetSocketAddress(TvTarget.host(host), port), 4000); s.startHandshake() }
            catch (e: Exception) { s.close(); throw e }
        }
    }
    private fun tick() {
        if (!wanted || pairing) return
        val context = app ?: return
        if (retryRequested) { retryRequested = false; disconnect(); nextConnect = 0 }
        var s = socket
        if (s == null) {
            if (now() < nextConnect) return
            nextConnect = now() + 5000
            val p = prefs(context); val pin = p.getString("pin", null) ?: run { status = "Pair TV first"; return }
            status = "Paired · Connecting to TV…"
            val host=candidates.poll() ?: p.getString("host", "").orEmpty()
            val attempt = generation
            s = tls(context, host, 6466, pin)
            if (!wanted || pairing || attempt != generation) { s.close(); return }
            p.edit().putString("host",host).apply()
            discovery?.stop();candidates.clear()
            socket = s; lastPacket = now(); session = AirSession(now(), com.mirror.app.BuildConfig.VERSION_NAME); status = "Negotiating remote…"
        }
        session.checkDeadline(now())
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
            if (connected) { status = "Connected · Android TV Remote"; lastFailure = "" }
            else status = "Negotiating · ${session.stage}"
        }
        check(now() - lastPacket < 30_000) { "TV stopped responding" }
        session.checkDeadline(now())
        keys.poll()?.let { (key, at) ->
            if (connected && now() - at < 500) {
                try {
                    AirWire.write(s.outputStream, session.key(key))
                    lastCommand = "$key · transmitted (execution not confirmed)"; onSent?.invoke(key)
                } catch (e: Exception) { lastCommand = "$key · transmission failed: ${e.message}"; throw e }
            } else lastCommand = "$key · discarded: expired or disconnected"
        }
    }
    fun pair(context: Context, address: String) {
        if (pairing) return
        val host = TvTarget.host(address)
        init(context);discovery?.stop();candidates.clear(); pairing = true; generation++; val attempt = generation; codes.clear(); disconnect(); status = "Requesting pairing code on TV…"
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
                    prefs(context).edit().putString("host", host).putString("pin", AirTls.fingerprint(server)).apply()
                    nextConnect = 0
                    if (attempt == generation) { status = "Paired · connecting…"; connect(context.applicationContext) } else status = "Paired · disconnected"
                }
            } catch (e: Exception) { status = "Pairing failed: ${e.message}. Check Android TV Remote Service on the TV." }
            finally { pairing = false }
        }
    }
    fun code(raw: String) { val code = raw.trim().uppercase(); require(code.matches(Regex("[0-9A-F]{6}"))) { "Enter six hexadecimal characters from TV" }; check(pairing) { "Start pairing first" }; codes.offer(code) }
}
