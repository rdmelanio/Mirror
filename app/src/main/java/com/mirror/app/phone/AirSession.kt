package com.mirror.app.phone

/** State belongs to one TLS connection. POWER enables the RemoteStart power-state report. */
internal class AirSession(private val startedAt: Long = 0, private val version: String = "1.12.2") {
    var ready = false; private set
    var stage = "waiting for RemoteConfigure"; private set
    private var features = 0
    private var configured = false
    private var activated = false
    fun checkDeadline(now: Long) {
        check(ready || now - startedAt < 12_000) { "Remote handshake timed out: $stage" }
    }
    fun receive(data: ByteArray): ByteArray? {
        val message = AirWire.fields(data)
        return when {
            3 in message -> error("TV rejected remote request (RemoteError)")
            1 in message -> {
                ready = false; activated = false
                // Only request implemented features: PING, KEY and POWER (RemoteStart).
                features = AirWire.value(AirWire.fields(message.getValue(1))[1]) and 35
                check(features and 2 != 0) { "TV does not advertise remote key support" }
                configured = true; stage = "waiting for RemoteSetActive (features=$features)"
                val info = AirWire.number(3, 1) + AirWire.text(4, "1") + AirWire.text(5, "com.mirror.app") + AirWire.text(6, version)
                AirWire.bytes(1, AirWire.number(1, features) + AirWire.bytes(2, info))
            }
            2 in message -> {
                check(configured) { "RemoteSetActive arrived before RemoteConfigure" }
                activated = true
                // A server without POWER cannot be required to emit RemoteStart.
                ready = features and 32 == 0
                stage = if (ready) "ready" else "waiting for RemoteStart"
                AirWire.bytes(2, AirWire.number(1, features))
            }
            8 in message -> {
                // val1 is int32, including negative ten-byte protobuf varints. Echo its wire value.
                val value = AirWire.fields(message.getValue(8))[1] ?: byteArrayOf(0)
                AirWire.bytes(9, byteArrayOf(8) + value)
            }
            40 in message -> {
                check(configured && activated) { "RemoteStart arrived before activation" }
                // 'started' is TV power state, not handshake success; false still completes negotiation.
                ready = true; stage = "ready"; null
            }
            else -> null
        }
    }
    fun key(command: AirGestures.Command): ByteArray { check(ready) { "TV handshake incomplete" }; return AirWire.key(command) }
}
