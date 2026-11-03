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
    fun requestV2(nonce:String,after:Long,revision:Long)="air-feedback-v2:$nonce:$after:$revision"
    fun request(nonce: String, after: Long) = "air-feedback-v1:$nonce:$after"
}

internal class AirFeedbackEvents(private val now: () -> Long = { System.nanoTime()/1_000_000 }) {
    private data class Event(val id:Long,val at:Long,val name:String)
    private val lock=Object();private val epoch=AirFeedbackAuth.nonce();private var seq=0L
    private val queue=java.util.ArrayDeque<Event>()
    private var state=JSONObject();private var revision=0L;private var updated=0L
    private var activity=-1L;private var activityAt=0L;private var tracking="NONE";private var trackingAt=0L
    @Volatile private var closed=false
    fun post(value:String) { require(value in AirFeedbackDisplay.events);synchronized(lock) {
        if(!closed) { queue.add(Event(++seq,now(),value));while(queue.size>16) queue.removeFirst();lock.notifyAll() }
    } }
    fun snapshot(value:JSONObject) = synchronized(lock) {
        val t=now()
        if(value.optLong("activity",-1)!=activity) { activity=value.optLong("activity",-1);activityAt=t }
        if(value.optString("tracking")!=tracking) { tracking=value.optString("tracking");trackingAt=t }
        if(value.toString()!=state.toString()) { state=JSONObject(value.toString());revision++;updated=t;lock.notifyAll() }
    }
    fun poll(after:Long,waitMs:Long=10_000,stateAfter:Long?=null):String=synchronized(lock) {
        val start=now();val until=start+waitMs.coerceIn(0,10_000);val before=stateAfter ?: revision
        while(!closed && after==seq && before==revision && now()<until) lock.wait((until-now()).coerceAtLeast(1))
        val pending=if(closed || after<0) emptyList() else queue.filter { it.id>after && now()-it.at<=2000 }
        val first=pending.firstOrNull()
        val items=org.json.JSONArray();pending.forEach { items.put(JSONObject().put("id",it.id).put("event",it.name).put("ageMs",now()-it.at)) }
        val legacy=when(first?.name) { "TRANSFERRED" -> "ACTIVE";else -> first?.name.orEmpty() }
        JSONObject().put("version",2).put("epoch",epoch).put("seq",first?.id ?: seq).put("waitMs",(now()-start).coerceAtLeast(0))
            .put("event",legacy).put("events",items)
            .put("snapshot",JSONObject(state.toString()).put("revision",revision).put("ageMs",now()-updated)
                .put("interactionAgeMs",now()-activityAt).put("trackingAgeMs",now()-trackingAt)).toString()
    }
    fun close() { synchronized(lock) { closed=true;lock.notifyAll() } }
}

internal object AirFeedbackDisplay {
    val events=setOf("ACTIVE","TRANSFERRED","EXPIRED","CLAIMING","SENT_LEFT","SENT_RIGHT","SENT_UP","SENT_DOWN","SENT_OK","SENT_BACK","SENT_HOME")
    fun caption(event: String): String? = when(event) {
        "ACTIVE" -> "Air Remote Active"
        "TRANSFERRED" -> "Control transferred"
        "EXPIRED" -> "Air Remote inactive\nRaise open palm to activate"
        "CLAIMING" -> "Hold open palm…"
        "SENT_LEFT" -> "LEFT sent"; "SENT_RIGHT" -> "RIGHT sent"; "SENT_UP" -> "UP sent"; "SENT_DOWN" -> "DOWN sent"
        "SENT_OK" -> "OK sent"; "SENT_BACK" -> "BACK sent"; "SENT_HOME" -> "HOME sent"
        else -> null
    }
    fun duration(event:String)=when(event) { "ACTIVE","EXPIRED" -> 2000L;"TRANSFERRED" -> 1500L;"CLAIMING" -> 1000L;else -> 1200L }
}
