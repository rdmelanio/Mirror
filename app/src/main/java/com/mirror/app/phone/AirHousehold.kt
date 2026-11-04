package com.mirror.app.phone

import kotlin.math.*

/** Spatial continuity is a safety heuristic, not personal identity. Session authority outlives observations. */
internal class AirHousehold(private val gestures:AirGestures,private val pointing:AirPointCommands,
    private val onClaim:()->Unit,private val send:(AirGestures.Command)->Unit,private val launch:(AirApp)->Unit={}) {
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
    val trackedHand get()=owner.takeIf { tracking==Tracking.OWNED }
    private val joystick=AirJoystick()
    private val shortcuts=AirShortcuts()
    var navigation=AirNavigation.POINTING
    var joystickSensitivity=50
    var apps=emptySet<AirApp>()
    var appSupported=false
    var acceptedApp:AirApp?=null;private set
    private var navigationWas=AirNavigation.POINTING
    private var modeRelease=false;private var modeNeutralAt=-1L
    private var velocityX=0f;private var velocityY=0f
    private var seenAt=-1L
    private var recovery:AirOwnership.Hand?=null
    private var recoveryAt=-1L
    private var recoverySamples=0
    private var ambiguous=false
    private var competitionAt=-1L
    private var singleSince=-1L
    private var ambiguityOwner:AirOwnership.Hand?=null
    private var rivals=emptyList<AirOwnership.Hand>()
    private var wasConnected=true
    private var pendingDeadline=-1L
    private val claims=AirOwnership().apply { claimDurationMs=1000;claimSamples=3;minimumHandSize=.005f }
    private fun distance(a:AirOwnership.Hand,b:AirOwnership.Hand)=hypot(a.x-b.x,a.y-b.y)
    private fun plausible(a:AirOwnership.Hand,b:AirOwnership.Hand,gap:Long):Boolean {
        if(a.bodyId!=null && b.bodyId!=null && a.bodyId!=b.bodyId) return false
        val dt=gap.coerceIn(0,2400)/1000f
        val speed=hypot(velocityX,velocityY)
        val radius=(maxOf(.045f,a.size*2f)+minOf(.07f,dt*.035f+speed*dt*.6f)).coerceAtMost(.24f)
        val px=a.x+velocityX*minOf(dt,.7f);val py=a.y+velocityY*minOf(dt,.7f)
        val prediction=hypot(b.x-px,b.y-py)
        return (distance(a,b)<=radius || gap<=2400 && prediction<maxOf(.06f,minOf(.14f,a.size*1.8f+dt*.05f))) && b.size/a.size.coerceAtLeast(.005f) in .18f..4f
    }
    private fun emit(value:String) { event=value;eventSerial++ }
    private fun interact(now:Long) { lastActivityAt=now;activitySerial++ }
    fun reset(reason:String="explicit reset") {
        active=false;currentPose="None";currentScore=0f;blocked.clear();owner=null;seenAt=-1;recovery=null;recoverySamples=0;ambiguous=false;ambiguityOwner=null;rivals=emptyList();competitionAt=-1;singleSince=-1;claims.reset();claimProgress=0
        gestures.reset();pointing.reset();joystick.reset();shortcuts.reset();acceptedApp=null;velocityX=0f;velocityY=0f;accepted=null;tracking=Tracking.NONE;pendingDeadline=-1;awaitingRelease=false
        status="Disarmed · $reason"
    }
    fun pause(reason:String="recognition temporarily unavailable") {
        if(!active) return
        pendingDeadline=-1;joystick.pause(seenAt.coerceAtLeast(0));shortcuts.pause();tracking=Tracking.LOST;gestures.pause(lastActivityAt);pointing.pause();recovery=null;recoverySamples=0
        status="Tracking temporarily unavailable · $reason"
    }
    fun tick(now:Long) {
        if(!active) return
        if(tracking==Tracking.OWNED && seenAt>=0 && now-seenAt>2000) pause("fresh observations unavailable")
        if(now-lastActivityAt>=timeoutMs && (pendingDeadline<now || pendingDeadline<0)) {
            reset("inactivity timeout");awaitingRelease=true;emit("EXPIRED")
        }
    }
    fun recoveryProcessing(now:Long)=active && seenAt>=0 && now-seenAt in 0..12_000 && tracking in setOf(Tracking.LOST,Tracking.RECOVERING,Tracking.AMBIGUOUS)
    fun processing(now:Long)=active && seenAt>=0 && now-seenAt<2400 && tracking!=Tracking.CONNECTION
    private fun install(now:Long,h:AirOwnership.Hand,transfer:Boolean,calibration:Boolean) {
        val initial=!active
        if(transfer && owner!=null) { blocked.add(Blocked(owner!!));while(blocked.size>8) blocked.removeAt(0) }
        active=true;currentPose=h.gesture;currentScore=h.confidence;owner=h;seenAt=now;tracking=Tracking.OWNED;recovery=null;recoverySamples=0;ambiguous=false;ambiguityOwner=null;rivals=emptyList()
        claims.reset();claimProgress=0;pointing.reset();joystick.reset();shortcuts.reset();gestures.claim(now,h.x,h.y);gestures.trackingGraceMs=Long.MAX_VALUE
        interact(now);status=if(transfer) "Control transferred · Active" else "Active · hand owned"
        emit(if(transfer) "TRANSFERRED" else "ACTIVE")
        if(initial && !calibration) onClaim()
    }
    fun update(now:Long,hands:List<AirOwnership.Hand>,connected:Boolean,calibration:Boolean,confidence:Float,sensitivity:Int,legacy:Boolean,holdMs:Long) {
        accepted=null;acceptedApp=null;currentPose="None";currentScore=0f;pointing.holdMs=holdMs;gestures.sensitivity=sensitivity;gestures.minimumConfidence=confidence
        val valid=hands.filter { it.x.isFinite() && it.y.isFinite() && it.size.isFinite() && it.size>=.005f }
        updateBlocked(now,valid)
        if(navigationWas!=navigation) {
            pointing.reset();joystick.reset();shortcuts.pause();navigationWas=navigation;modeRelease=active;modeNeutralAt=-1
        }
        if(awaitingRelease) {
            if(valid.any { it.gesture=="Open_Palm" }) { status="Disarmed · relax palm before a new claim";return }
            awaitingRelease=false
        }
        if(connected!=wasConnected) { joystick.reset();shortcuts.pause();pointing.pause();gestures.pause(now);recovery=null;recoverySamples=0;wasConnected=connected;if(active) tracking=if(connected) Tracking.RECOVERING else Tracking.CONNECTION }
        val ref=owner
        val nearby=if(ref==null) emptyList() else valid.filter { plausible(ref,it,now-seenAt) }
        val uncertain=nearby.size>1 || nearby.singleOrNull()?.let { h -> valid.any { it!==h && distance(it,h)<maxOf(.035f,minOf(.12f,h.size*1.2f)) } }==true
        val priorTracking=tracking
        if(valid.size>1) { competitionAt=now;singleSince=-1 }
        else if(valid.size==1) { if(singleSince<0) singleSince=now } else singleSince=-1
        var selected=nearby.singleOrNull()?.takeUnless { uncertain }
        var resolvingTie=false
        if(uncertain) {
            if(!ambiguous && ref!=null) {
                val ordered=nearby.sortedBy { distance(ref,it)+abs(ln(it.size/ref.size.coerceAtLeast(.01f)))*.025f }
                val best=ordered.firstOrNull()
                val first=best?.let { distance(ref,it)+abs(ln(it.size/ref.size.coerceAtLeast(.01f)))*.025f } ?: 1f
                val second=ordered.getOrNull(1)?.let { distance(ref,it)+abs(ln(it.size/ref.size.coerceAtLeast(.01f)))*.025f } ?: 1f
                ambiguityOwner=best.takeIf { second-first>=.018f }
                rivals=nearby.filter { it!==ambiguityOwner }
            }
            ambiguous=true;selected=null;recovery=null;recoverySamples=0
        } else if(ambiguous && selected!=null) {
            // Evidence, not elapsed time: the remaining rival cannot inherit a paused owner's key.
            var a=ambiguityOwner
            if(a==null && ref!=null) {
                val tight=maxOf(.012f,minOf(.045f,ref.size*.65f))
                val margin=maxOf(.003f,minOf(.008f,ref.size*.15f))
                val scale=selected.size/ref.size.coerceAtLeast(.005f)
                // Resolve a previous tie only with new evidence favoring the old reference over ALL rivals.
                if(distance(ref,selected)<=tight && scale in .6f..1.7f && rivals.all { distance(it,selected)>distance(ref,selected)+margin }) {
                    a=ref;resolvingTie=true
                }
            }
            val reference=a
            if(reference==null || distance(reference,selected)>.10f || !resolvingTie && rivals.any { distance(it,selected)<=distance(reference,selected)+.012f }) selected=null
        }
        // Bounded scene evidence replaces stale spatial rivals, never a current competitor.
        // A distant household member still needs a palm; allow natural seated repositioning.
        if(active && selected==null && !uncertain && valid.size==1 && ref!=null &&
            now-seenAt>=600 && (competitionAt<0 || now-competitionAt>=2400) &&
            singleSince>=0 && now-singleSince>=400) {
            val only=valid.single()
            if((only.bodyCount<=1 || ref.bodyId!=null && ref.bodyId==only.bodyId) &&
                (ref.bodyId==null || only.bodyId==null || ref.bodyId==only.bodyId || now-seenAt>6000 && only.bodyCount<=1) &&
                (distance(ref,only)<=.32f || ref.bodyId!=null && ref.bodyId==only.bodyId) && only.size/ref.size.coerceAtLeast(.005f) in .35f..2.8f) {
                selected=only;resolvingTie=true
            }
        }
        if(active && selected!=null && priorTracking!=Tracking.OWNED && priorTracking!=Tracking.CONNECTION) {
            // Recovery requires two spatially consistent observations. Nothing pending survives the gap.
            if(recovery==null || distance(recovery!!,selected)>.10f || now-recoveryAt>1200) { recovery=selected;recoveryAt=now;recoverySamples=0 }
            recoverySamples++
            if(recoverySamples<(if(resolvingTie) 3 else 2) || now-recoveryAt<(if(resolvingTie) 400 else 150)) { selected=null;tracking=Tracking.RECOVERING }
            else { ambiguous=false;ambiguityOwner=null;rivals=emptyList();joystick.pause(now);shortcuts.pause();pointing.pause();gestures.pause(now);status="Tracking recovered · Active" }
        }
        if(active && selected!=null) {
            currentPose=selected.gesture;currentScore=selected.confidence
            val before=owner
            if(before!=null && seenAt>=0 && now>seenAt) {
                velocityX=(velocityX*.5f+(selected.x-before.x)*1000/(now-seenAt)*.5f).coerceIn(-.5f,.5f)
                velocityY=(velocityY*.5f+(selected.y-before.y)*1000/(now-seenAt)*.5f).coerceIn(-.5f,.5f)
            }
            owner=if(before==null || priorTracking!=Tracking.OWNED) selected else selected.copy(x=before.x*.25f+selected.x*.75f,y=before.y*.25f+selected.y*.75f,size=before.size*.35f+selected.size*.65f)
            seenAt=now;tracking=Tracking.OWNED;recoverySamples=0;recovery=null
            // The old command interpreter's timeout is not the household session authority.
            gestures.activity(now)
            run {
                if(modeRelease) {
                    if(selected.gesture in setOf("Open_Palm","Closed_Fist","None","")) {
                        if(modeNeutralAt<0) modeNeutralAt=now
                        if(now-modeNeutralAt>=200) modeRelease=false
                    } else modeNeutralAt=-1
                    status="Active · navigation mode changed; relax hand before commands"
                }
                val useJoystick=!legacy && navigation==AirNavigation.JOYSTICK
                val pointName=if(useJoystick && selected.gesture in setOf("POINT_UP","POINT_DOWN","POINT_LEFT","POINT_RIGHT","Pointing_Up")) "None" else selected.gesture
                val command=if(modeRelease) null else if(legacy) gestures.update(now,selected.gesture,selected.confidence,selected.x,selected.y,selected.size)
                    else {
                        val move=if(useJoystick) joystick.update(now,selected,joystickSensitivity) else null
                        val pose=pointing.update(now,pointName,selected.confidence,confidence)
                        move ?: pose
                    }
                val app=if(!legacy && !modeRelease) shortcuts.update(now,selected.gesture,selected.confidence,confidence,apps) else null
                if(app!=null && connected && appSupported && !calibration) { acceptedApp=app;interact(now);launch(app) }
                if(shortcuts.pending && shortcuts.activityAt>lastActivityAt) interact(now)
                if(useJoystick && joystick.activityAt>lastActivityAt) interact(now)
                if(pointing.pending && pendingDeadline<0 && now-lastActivityAt<timeoutMs) pendingDeadline=lastActivityAt+timeoutMs+1400
                if(!legacy && pointing.activityAt>lastActivityAt && pointing.activityStartedAt<=lastActivityAt+timeoutMs && now-lastActivityAt<=timeoutMs+1400) { interact(now);pendingDeadline=-1 }
                if(command!=null && now-lastActivityAt<timeoutMs) {
                    interact(now);pendingDeadline=-1
                    if(connected || calibration) { accepted=command;lastCommandAt=now;if(!calibration) send(command) }
                }
                if(priorTracking==Tracking.OWNED) status="Active · hand owned · ${if(legacy) gestures.movement else if(shortcuts.pending) shortcuts.reason else if(useJoystick && (selected.indexValid || selected.gesture in setOf("","None"))) joystick.reason else pointing.reason}"
            }
            if(!connected && !calibration) { tracking=Tracking.CONNECTION;status="Session valid · TV transport unavailable; commands blocked" }
        } else if(active) {
            pendingDeadline=-1
            if(tracking!=Tracking.RECOVERING) tracking=if(ambiguous) Tracking.AMBIGUOUS else Tracking.LOST
            joystick.pause(now);shortcuts.pause();pointing.pause();gestures.pause(now)
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
