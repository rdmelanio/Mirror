package com.mirror.app.phone

import kotlin.math.*

/** Spatial continuity is a safety heuristic, not personal identity. Session authority outlives observations. */
internal class AirHousehold(private val gestures:AirGestures,private val pointing:AirPointCommands,
    private val onClaim:()->Unit,private val send:(AirGestures.Command)->Unit) {
    enum class Tracking { NONE, OWNED, LOST, RECOVERING, AMBIGUOUS, CONNECTION }
    var active=false;private set
    var tracking=Tracking.NONE;private set
    var status="Standby";private set
    var lastActivityAt=-1L;private set
    var lastCommandAt=-1L;private set
    var accepted:AirGestures.Command?=null;private set
    var activitySerial=0L;private set
    var eventSerial=0L;private set
    var event="";private set
    var claimProgress=0L;private set
    var awaitingRelease=false;private set
    var timeoutMs=60_000L
    var currentPose="None";private set
    var currentScore=0f;private set
    private data class Blocked(var hand:AirOwnership.Hand,var releaseAt:Long=-1,var observations:Int=0)
    private val blocked=mutableListOf<Blocked>()
    private fun updateBlocked(now:Long,hands:List<AirOwnership.Hand>) {
        val it=blocked.iterator()
        while(it.hasNext()) {
            val b=it.next();val near=hands.filter { h -> distance(b.hand,h)<.16f }.singleOrNull()
            if(near?.gesture=="Open_Palm") { b.hand=near;b.releaseAt=-1;b.observations=0 }
            else {
                if(b.releaseAt<0) b.releaseAt=now
                b.observations++
                if(b.observations>=2 && now-b.releaseAt>=300) it.remove()
            }
        }
    }
    private var owner:AirOwnership.Hand?=null
    private var seenAt=-1L
    private var recovery:AirOwnership.Hand?=null
    private var recoveryAt=-1L
    private var recoverySamples=0
    private var ambiguous=false
    private var wasConnected=true
    private var pendingDeadline=-1L
    private val claims=AirOwnership().apply { claimDurationMs=1000;claimSamples=3 }
    private fun distance(a:AirOwnership.Hand,b:AirOwnership.Hand)=hypot(a.x-b.x,a.y-b.y)
    private fun plausible(a:AirOwnership.Hand,b:AirOwnership.Hand,gap:Long):Boolean =
        distance(a,b)<=(if(gap>2000) .14f else .24f) && b.size/a.size.coerceAtLeast(.02f) in .18f..4f
    private fun emit(value:String) { event=value;eventSerial++ }
    private fun interact(now:Long) { lastActivityAt=now;activitySerial++ }
    fun reset(reason:String="explicit reset") {
        active=false;currentPose="None";currentScore=0f;blocked.clear();owner=null;seenAt=-1;recovery=null;recoverySamples=0;ambiguous=false;claims.reset();claimProgress=0
        gestures.reset();pointing.reset();accepted=null;tracking=Tracking.NONE;pendingDeadline=-1;awaitingRelease=false
        status="Disarmed · $reason"
    }
    fun pause(reason:String="recognition temporarily unavailable") {
        if(!active) return
        pendingDeadline=-1;tracking=Tracking.LOST;gestures.pause(lastActivityAt);pointing.pause();recovery=null;recoverySamples=0
        status="Tracking temporarily unavailable · $reason"
    }
    fun tick(now:Long) {
        if(!active) return
        if(tracking==Tracking.OWNED && seenAt>=0 && now-seenAt>2000) pause("fresh observations unavailable")
        if(now-lastActivityAt>=timeoutMs && (pendingDeadline<now || pendingDeadline<0)) {
            reset("inactivity timeout");awaitingRelease=true;emit("EXPIRED")
        }
    }
    fun processing(now:Long)=active && tracking==Tracking.OWNED && (now-lastActivityAt<2200 || pointing.pending)
    private fun install(now:Long,h:AirOwnership.Hand,transfer:Boolean,calibration:Boolean) {
        val initial=!active
        if(transfer && owner!=null) { blocked.add(Blocked(owner!!));while(blocked.size>8) blocked.removeAt(0) }
        active=true;currentPose=h.gesture;currentScore=h.confidence;owner=h;seenAt=now;tracking=Tracking.OWNED;recovery=null;recoverySamples=0;ambiguous=false
        claims.reset();claimProgress=0;pointing.reset();gestures.claim(now,h.x,h.y);gestures.trackingGraceMs=Long.MAX_VALUE
        interact(now);status=if(transfer) "Control transferred · Active" else "Active · hand owned"
        emit(if(transfer) "TRANSFERRED" else "ACTIVE")
        if(initial && !calibration) onClaim()
    }
    fun update(now:Long,hands:List<AirOwnership.Hand>,connected:Boolean,calibration:Boolean,confidence:Float,sensitivity:Int,legacy:Boolean,holdMs:Long) {
        accepted=null;currentPose="None";currentScore=0f;pointing.holdMs=holdMs;gestures.sensitivity=sensitivity;gestures.minimumConfidence=confidence
        val valid=hands.filter { it.x.isFinite() && it.y.isFinite() && it.size.isFinite() && it.size>=.01f }
        updateBlocked(now,valid)
        if(awaitingRelease) {
            if(valid.any { it.gesture=="Open_Palm" }) { status="Disarmed · relax palm before a new claim";return }
            awaitingRelease=false
        }
        if(connected!=wasConnected) { pointing.pause();gestures.pause(now);recovery=null;recoverySamples=0;wasConnected=connected;if(active) tracking=if(connected) Tracking.RECOVERING else Tracking.CONNECTION }
        val ref=owner
        val nearby=if(ref==null) emptyList() else valid.filter { plausible(ref,it,now-seenAt) }
        val uncertain=nearby.size>1 || nearby.singleOrNull()?.let { h -> valid.any { it!==h && distance(it,h)<.12f } }==true
        val priorTracking=tracking
        var selected=nearby.singleOrNull()?.takeUnless { uncertain || ambiguous }
        if(uncertain) { ambiguous=true;selected=null }
        if(active && selected!=null && priorTracking!=Tracking.OWNED && priorTracking!=Tracking.CONNECTION) {
            // Recovery requires two spatially consistent observations. Nothing pending survives the gap.
            if(recovery==null || distance(recovery!!,selected)>.10f || now-recoveryAt>1200) { recovery=selected;recoveryAt=now;recoverySamples=0 }
            recoverySamples++
            if(recoverySamples<2 || now-recoveryAt<150) { selected=null;tracking=Tracking.RECOVERING }
            else { pointing.pause();gestures.pause(now);status="Tracking recovered · Active" }
        }
        if(active && selected!=null) {
            currentPose=selected.gesture;currentScore=selected.confidence
            owner=selected;seenAt=now;tracking=Tracking.OWNED;recoverySamples=0;recovery=null
            // The old command interpreter's timeout is not the household session authority.
            gestures.activity(now)
            run {
                val command=if(legacy) gestures.update(now,selected.gesture,selected.confidence,selected.x,selected.y,selected.size)
                    else pointing.update(now,selected.gesture,selected.confidence,confidence)
                if(pointing.pending && pendingDeadline<0 && now-lastActivityAt<timeoutMs) pendingDeadline=lastActivityAt+timeoutMs+1400
                if(!legacy && pointing.activityAt>lastActivityAt && pointing.activityStartedAt<=lastActivityAt+timeoutMs && now-lastActivityAt<=timeoutMs+1400) { interact(now);pendingDeadline=-1 }
                if(command!=null && now-lastActivityAt<timeoutMs) {
                    interact(now);pendingDeadline=-1
                    if(connected || calibration) { accepted=command;lastCommandAt=now;if(!calibration) send(command) }
                }
                if(priorTracking==Tracking.OWNED) status="Active · hand owned · ${if(legacy) gestures.movement else pointing.reason}"
            }
            if(!connected && !calibration) { tracking=Tracking.CONNECTION;status="Session valid · TV transport unavailable; commands blocked" }
        } else if(active) {
            pendingDeadline=-1
            if(tracking!=Tracking.RECOVERING) tracking=if(ambiguous) Tracking.AMBIGUOUS else Tracking.LOST
            pointing.pause();gestures.pause(now)
            status=if(ambiguous) "Session valid · ambiguous ownership; commands blocked" else "Session valid · tracking temporarily unavailable"
        }
        // A background hand cannot compete with a controller who is still deliberately interacting.
        val canRequest=!active || selected==null || now-lastActivityAt>=1600
        val candidates=(if(!active) valid else valid.filter { h -> selected==null || h!==selected }).filter { h -> blocked.none { distance(it.hand,h)<.16f } }
        if(canRequest) {
            claims.minimumConfidence=confidence
            val r=claims.update(now,candidates)
            claimProgress=claims.progressMs
            if(active && claimProgress>=400 && now-lastActivityAt<timeoutMs && pendingDeadline<0) pendingDeadline=lastActivityAt+timeoutMs+1400
            val winner=if(r.claimed) r.hand else if(claims.claimed) r.hand else null
            if(winner!=null && (!active || now-lastActivityAt>=1200) && candidates.count { it.gesture=="Open_Palm" && it.confidence>=confidence }==1) {
                install(now,winner,active,calibration)
            }
        } else { claims.reset();claimProgress=0 }
        tick(now)
        if(!active && !awaitingRelease) status=if(claimProgress>0) "Claiming control · ${claimProgress.coerceAtMost(1000)}/1000 ms" else "Standby"
    }
}
