package com.mirror.app.phone

import org.json.JSONObject

/** A single authorized TV owns each expiring request. IDs, not key commands, are acknowledged. */
internal class AirLaunchMailbox(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private data class Request(val id:String,val app:AirApp,val key:String,val until:Long)
    private var peer="";private var peerAt=-1L;private var request:Request?=null
    private var message="App shortcuts: waiting for compatible TV listener"
    val status:String get()=synchronized(this) {
        if(request?.let { clock()>=it.until }==true) { message="${request!!.app.name} · launch request expired";request=null }
        message
    }
    @Synchronized fun cancel() { request?.let { message="${it.app.name} · launch request discarded: calibration / disabled" };request=null }
    @Synchronized fun supported()=peer.isNotEmpty() && clock()-peerAt<15_000
    @Synchronized fun authorize(key:String) { peer=key;peerAt=clock() }
    @Synchronized fun submit(app:AirApp):Boolean {
        if(!supported()) { message="${app.name} · discarded: compatible TV listener unavailable";return false }
        if(request?.let { clock()<it.until }==true) { message="${app.name} · discarded: launch already pending";return false }
        request=Request(AirFeedbackAuth.nonce(),app,peer,clock()+3500);message="${app.name} · launch request accepted; awaiting TV";return true
    }
    @Synchronized fun payload(key:String):JSONObject? {
        val r=request ?: return null
        if(clock()>=r.until) { message="${r.app.name} · launch request expired";request=null;return null }
        return if(key==r.key) JSONObject().put("id",r.id).put("app",r.app.name).put("remainingMs",r.until-clock()) else null
    }
    @Synchronized fun ack(key:String,value:JSONObject) {
        val r=request ?: return
        if(key!=r.key || value.optString("id")!=r.id || clock()>=r.until) return
        val result=value.optString("result")
        if(result !in setOf("ATTEMPTED","BLOCKED","MISSING","EXPIRED","FAILED")) return
        message="${r.app.name} · " + when(result) {
            "ATTEMPTED" -> "TV submitted launch intent; app opening not confirmed"
            "BLOCKED" -> "Android blocked background launch; open Mirror / allow overlay permission"
            "MISSING" -> "TV app not installed"
            "EXPIRED" -> "launch request expired at TV"
            else -> "TV launch attempt failed"
        }
        request=null
    }
}

/** TV replay guard. Persist reserve() before any activity side effect, including across service restarts. */
internal class AirLaunchReceiver(saved:List<String> = emptyList()) {
    private val ids=java.util.ArrayDeque<String>().apply { saved.takeLast(48).forEach(::add) }
    fun reserve(value:JSONObject,transitMs:Long):Pair<String,AirApp>? {
        val id=value.optString("id");val remaining=value.optLong("remainingMs")-transitMs
        if(!id.matches(Regex("[0-9a-f]{32}")) || id in ids || remaining !in 1..3500) return null
        val app=AirApp.entries.firstOrNull { it.name==value.optString("app") } ?: return null
        ids.add(id);while(ids.size>48) ids.removeFirst()
        return id to app
    }
    fun saved()=ids.toList()
}
