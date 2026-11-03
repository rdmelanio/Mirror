package com.mirror.app.phone

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticators use the existing pairing secret's stored digest, never transmit it or the token. */
internal object AirFeedbackAuth {
    fun key(token: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    fun nonce() = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sign(key: String, body: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getUrlDecoder().decode(key),"HmacSHA256")) }.doFinal(body.toByteArray(Charsets.UTF_8)))
    fun valid(key: String, body: String, mac: String) = runCatching {
        MessageDigest.isEqual(sign(key,body).toByteArray(Charsets.US_ASCII),mac.toByteArray(Charsets.US_ASCII))
    }.getOrDefault(false)
    fun request(nonce: String, after: Long) = "air-feedback-v1:$nonce:$after"
}

internal class AirFeedbackEvents(private val now: () -> Long = { System.nanoTime()/1_000_000 }) {
    private val lock = Object()
    private val epoch = AirFeedbackAuth.nonce()
    private var seq=0L
    private var at=0L
    private var event=""
    @Volatile private var closed=false
    fun post(value: String) { require(value in AirFeedbackDisplay.events); synchronized(lock) { if(!closed) { seq++;at=now();event=value;lock.notifyAll() } } }
    fun poll(after: Long, waitMs: Long = 10_000): String = synchronized(lock) {
        val start=now(); val until=start+waitMs.coerceIn(0,10_000)
        while(!closed && after==seq && now()<until) lock.wait((until-now()).coerceAtLeast(1))
        JSONObject().put("epoch",epoch).put("seq",seq).put("waitMs",(now()-start).coerceAtLeast(0))
            .put("event",if(!closed && after>=0 && after!=seq && now()-at<=2000) event else "").toString()
    }
    fun close() { synchronized(lock) { closed=true;lock.notifyAll() } }
}

internal object AirFeedbackDisplay {
    val events=setOf("ACTIVE","SENT_LEFT","SENT_RIGHT","SENT_UP","SENT_DOWN","SENT_OK","SENT_BACK","SENT_HOME")
    fun caption(event: String): String? = when(event) {
        "ACTIVE" -> "Air Remote Active"
        "SENT_LEFT" -> "← sent"; "SENT_RIGHT" -> "→ sent"; "SENT_UP" -> "↑ sent"; "SENT_DOWN" -> "↓ sent"
        "SENT_OK" -> "OK sent"; "SENT_BACK" -> "Back sent"; "SENT_HOME" -> "Home sent"
        else -> null
    }
    fun duration(event: String) = if(event=="ACTIVE") 2000L else 600L
}
