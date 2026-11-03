package com.mirror.app.phone

/** Negotiation state is scoped to one TLS connection; keys are allowed only after RemoteStart. */
internal class AirSession {
    var ready = false; private set
    private var features = 3
    fun receive(data: ByteArray): ByteArray? {
        val message = AirWire.fields(data)
        return when {
            1 in message -> {
                features = AirWire.value(AirWire.fields(message.getValue(1))[1]) and 3
                check(features and 2 != 0) { "TV does not support remote keys" }
                val info = AirWire.number(3, 1) + AirWire.text(4, "1") + AirWire.text(5, "com.mirror.app") + AirWire.text(6, "1.12.1")
                AirWire.bytes(1, AirWire.number(1, features) + AirWire.bytes(2, info))
            }
            2 in message -> AirWire.bytes(2, AirWire.number(1, features))
            8 in message -> AirWire.bytes(9, AirWire.number(1, AirWire.value(AirWire.fields(message.getValue(8))[1])))
            40 in message -> { ready = true; null }
            3 in message -> error("TV rejected remote request")
            else -> null
        }
    }
    fun key(command: AirGestures.Command): ByteArray { check(ready) { "TV handshake incomplete" }; return AirWire.key(command) }
}
