package com.mirror.app.phone

import org.json.JSONObject

/** TV-owned visibility deadlines. Snapshots describe authority but cannot refresh visual timers by repetition. */
internal class AirFeedbackView {
    data class View(val text:String,val color:String,val until:Long)
    private var epoch="";private var cursor=-1L;private var revision=-1L
    private var activity=-1L;private var tracking="NONE"
    private var uncertainAt=-1L;private var amberDeadline=-1L
    private var hand:View?=null;private var message:View?=null
    private val seen=java.util.ArrayDeque<Long>()
    fun disconnect() { uncertainAt=-1;amberDeadline=-1;hand=null;message=null;epoch="";cursor=-1;revision=-1;activity=-1;tracking="NONE";seen.clear() }
    fun current(now:Long):View? {
        if(uncertainAt>=0 && now-uncertainAt>=650 && hand?.color=="GREEN" && hand!!.until>now) {
            hand=if(amberDeadline>now) View("","AMBER",amberDeadline) else null
        }
        if(message?.until?.let { it<=now }==true) message=null
        if(hand?.until?.let { it<=now }==true) hand=null
        return message ?: hand
    }
    fun accept(body:JSONObject,now:Long,fresh:Boolean=true,transitMs:Long=0) {
        if(!fresh) { disconnect();return }
        val nextEpoch=body.optString("epoch")
        val initial=epoch!=nextEpoch || cursor<0
        if(epoch!=nextEpoch) disconnect()
        epoch=nextEpoch
        val snapshot=body.optJSONObject("snapshot")
        if(snapshot!=null) {
            val nextRevision=snapshot.optLong("revision")
            val nextActivity=snapshot.optLong("activity",-1)
            val nextTracking=snapshot.optString("tracking","NONE")
            if(!initial && nextRevision>revision && snapshot.optLong("ageMs",Long.MAX_VALUE)<=5000-transitMs) {
                val age=snapshot.optLong("interactionAgeMs",Long.MAX_VALUE).let { if(it==Long.MAX_VALUE) it else it+transitMs }
                if(nextActivity>activity && age<4000 && snapshot.optBoolean("active") && nextTracking=="OWNED") {
                    hand=View("","GREEN",now+4000-age)
                }
                if(nextTracking!=tracking && uncertainAt<0 && nextTracking in setOf("LOST","RECOVERING","AMBIGUOUS","CONNECTION")) {
                    if(hand?.color=="GREEN" && hand!!.until>now) {
                        val lostAge=(snapshot.optLong("trackingAgeMs",0)+transitMs).coerceAtLeast(0)
                        uncertainAt=now-lostAge;amberDeadline=uncertainAt+10_000
                        if(lostAge>=10_000) hand=null
                    }
                }
                if(!snapshot.optBoolean("active")) { hand=null;uncertainAt=-1 }
                if(nextTracking=="OWNED") uncertainAt=-1
                if(nextTracking=="OWNED" && tracking!="OWNED" && hand?.color=="AMBER") {
                    hand=if(age<4000) View("","GREEN",now+4000-age) else null
                }
            }
            revision=maxOf(revision,nextRevision);activity=maxOf(activity,nextActivity);tracking=nextTracking
        }
        val events=body.optJSONArray("events")
        if(events!=null) {
            for(i in 0 until events.length()) {
                val e=events.getJSONObject(i);val id=e.getLong("id")
                if(!initial && id>cursor && id !in seen && e.optLong("ageMs",Long.MAX_VALUE)<=2000-transitMs) {
                    show(e.optString("event"),now)
                    seen.add(id);while(seen.size>32) seen.removeFirst()
                }
                cursor=maxOf(cursor,id)
            }
        } else if(!initial && body.optLong("seq")>cursor) show(body.optString("event"),now)
        cursor=maxOf(cursor,body.optLong("seq"))
    }
    fun launchResult(app:String,result:String,now:Long) {
        if(app !in AirApp.entries.map { it.name }) return
        val text=when(result) { "ATTEMPTED" -> "$app launch requested";"MISSING" -> "$app not installed";"BLOCKED" -> "$app launch blocked by Android";else -> "$app launch failed" }
        message=View(text,"WHITE",now+1800)
    }
    private fun show(event:String,now:Long) {
        val text=AirFeedbackDisplay.caption(event) ?: return
        message=View(text,if(event=="EXPIRED") "WHITE" else "GREEN",now+AirFeedbackDisplay.duration(event))
        if(event=="EXPIRED") hand=null
        // Legacy ACTIVE is always transient. Only authoritative fresh activity can show the lasting hand.
    }
}
