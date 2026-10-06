package com.mirror.app.phone

import io.github.muntashirakon.adb.AndroidPubkey
import io.github.muntashirakon.adb.PairingAuthCtx
import org.bouncycastle.tls.TlsClientProtocol
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/** Only the fixed wake-and-launch action is exposed. No arbitrary shell input in the UI. */
internal object TvAdbWire {
    const val LAUNCH = "input keyevent 224; am start -W -n com.mirror.app/.core.LauncherActivity"
    fun command(name: String): Int = ByteBuffer.wrap(name.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    data class Packet(val command: Int, val first: Int, val second: Int, val data: ByteArray)
    fun write(output: OutputStream, name: String, first: Int, second: Int, data: ByteArray = ByteArray(0)) {
        val c = command(name)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(c).putInt(first).putInt(second).putInt(data.size).putInt(data.sumOf { it.toInt() and 255 }).putInt(c.inv())
        output.write(header.array()); output.write(data); output.flush()
    }
    fun read(input: InputStream): Packet {
        val bytes = ByteArray(24); DataInputStream(input).readFully(bytes)
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val c = h.int; val a = h.int; val b = h.int; val length = h.int; val sum = h.int; val magic = h.int
        if (magic != c.inv() || length !in 0..1_048_576 || c !in listOf("CNXN", "STLS", "AUTH", "OKAY", "WRTE", "CLSE").map(::command))
            throw IOException("Invalid ADB packet")
        val data = ByteArray(length); DataInputStream(input).readFully(data)
        if (sum != 0 && sum != data.sumOf { it.toInt() and 255 }) throw IOException("Invalid ADB checksum")
        return Packet(c, a, b, data)
    }
    private fun pairingRead(input: DataInputStream, type: Int): ByteArray {
        val version = input.readUnsignedByte(); val actual = input.readUnsignedByte(); val size = input.readInt()
        if (version != 1 || actual != type || size !in 1..16384) throw IOException("Invalid pairing response")
        return ByteArray(size).also(input::readFully)
    }
    private fun pairingWrite(output: DataOutputStream, type: Int, bytes: ByteArray) {
        output.writeByte(1); output.writeByte(type); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    fun pair(socket: Socket, key: PrivateKey, certificate: X509Certificate, code: String): String {
        val client = TvTls(key, certificate)
        val tls = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        try {
            tls.connect(client)
            val binding = client.pairingBinding(); val password = code.toByteArray(Charsets.UTF_8) + binding
            val auth = try { PairingAuthCtx.createAlice(password) ?: throw IOException("Pairing unavailable") }
                finally { password.fill(0); binding.fill(0) }
            try {
                val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
                pairingWrite(output, 0, auth.msg)
                if (!auth.initCipher(pairingRead(input, 0))) throw IOException("Pairing code rejected")
                val publicKey = AndroidPubkey.encodeWithName(certificate.publicKey as RSAPublicKey, "Mirror Clock")
                val peer = ByteArray(8192); publicKey.copyInto(peer, 1) // type 0 = client RSA public key
                pairingWrite(output, 1, auth.encrypt(peer) ?: throw IOException("Pairing failed"))
                val remote = auth.decrypt(pairingRead(input, 1)) ?: throw IOException("Pairing code rejected or expired")
                if (remote.size != 8192 || remote[0].toInt() != 1) throw IOException("Invalid TV identity")
                val end = remote.indexOfFirst { it == 0.toByte() }.takeIf { it > 1 } ?: remote.size
                val guid = remote.copyOfRange(1, end).toString(Charsets.UTF_8)
                if (!guid.matches(Regex("[A-Za-z0-9_-]{1,200}"))) throw IOException("Invalid TV identity")
                return guid
            } finally { auth.destroy() }
        } finally { runCatching { tls.close() } }
    }
    fun launch(socket: Socket, key: PrivateKey, certificate: X509Certificate): String {
        var input = socket.getInputStream(); var output = socket.getOutputStream()
        write(output, "CNXN", 0x01000001, 1_048_576, "host::\u0000".toByteArray())
        val upgrade = read(input)
        if (upgrade.command != command("STLS")) throw IOException("Secure ADB required. Check wireless pairing.")
        write(output, "STLS", 0x01000000, 0)
        val tls = TlsClientProtocol(input, output)
        try {
            tls.connect(TvTls(key, certificate)); input = tls.inputStream; output = tls.outputStream
            val connected = read(input)
            if (connected.command != command("CNXN")) throw IOException("TV rejected pairing. Pair Mirror again.")
            write(output, "OPEN", 1, 0, ("shell:$LAUNCH\u0000").toByteArray())
            val text = ByteArrayOutputStream(); var remote = 0; var opened = false
            while (true) {
                val p = read(input)
                if (p.second != 1 || (remote != 0 && p.first != remote)) throw IOException("Invalid ADB stream")
                when (p.command) {
                    command("OKAY") -> { if (opened) throw IOException("Unexpected ADB reply"); remote = p.first; opened = true }
                    command("WRTE") -> {
                        if (!opened || text.size() + p.data.size > 65536) throw IOException("Invalid ADB output")
                        text.write(p.data); write(output, "OKAY", 1, remote)
                    }
                    command("CLSE") -> {
                        write(output, "CLSE", 1, p.first)
                        val result = text.toString("UTF-8")
                        if (!opened || result.contains("Error:") || result.contains("Exception") ||
                            !(result.contains("Starting: Intent") || result.contains("Activity not started")))
                            throw IOException("Mirror did not launch. Check that Mirror is installed on the TV.")
                        return "TV awake · Mirror launched"
                    }
                    else -> throw IOException("Unexpected ADB response")
                }
            }
        } finally { runCatching { tls.close() } }
    }
}
