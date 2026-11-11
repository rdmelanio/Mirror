package com.mirror.app.phone

/** Production boundary shared by camera inference and sequence regression tests. */
internal class AirControlSession(private val onClaim:()->Unit={},private val send:(AirGestures.Command)->Unit={},private val household:Boolean=false,private val launch:(AirApp)->Unit={}) {
    val gestures=AirGestures()
    val ownership=AirOwnership()
    val pointing=AirPointCommands()
    private val home=AirHousehold(gestures,pointing,onClaim,send,launch)
    val trackedHand get()=home.trackedHand
    val acceptedApp get()=home.acceptedApp
    val diagnosticPose get()=if(household) home.currentPose else "None"
    val diagnosticScore get()=if(household) home.currentScore else 0f
    val sessionActive get()=if(household) home.active else gestures.armed
    val trackingOwned get()=if(household) home.tracking==AirHousehold.Tracking.OWNED else ownership.claimed
    val trackingState get()=if(household) home.tracking.name else if(ownership.claimed) "OWNED" else "NONE"
    val claimProgress get()=if(household) home.claimProgress else ownership.progressMs
    val activitySerial get()=if(household) home.activitySerial else lastActivityAt
    val eventSerial get()=home.eventSerial
    val event get()=home.event
    fun recovering(now:Long)=household && home.recoveryProcessing(now)
    fun processing(now:Long)=if(household) home.processing(now) else gestures.armed
    fun pauseRecognition(now:Long) { if(household) { home.pause();syncHome() } else { gestures.reset();ownership.reset();pointing.reset() } }
    fun heartbeat(now:Long) { if(household) { home.tick(now);syncHome() } }
    private fun syncHome() { status=home.status;accepted=home.accepted;lastActivityAt=home.lastActivityAt;lastCommandAt=home.lastCommandAt;awaitingRelease=home.awaitingRelease }
    private var legacyWas=true
    var lastActivityAt=-1L;private set
    var lastCommandAt=-1L;private set
    var status="Standby";private set
    var accepted:AirGestures.Command?=null;private set
    var awaitingRelease=false;private set
    private var calibrationWas=false
    fun reset() { if(household) home.reset();lastActivityAt=-1;awaitingRelease=false;gestures.reset();ownership.reset();pointing.reset();status="Disarmed";accepted=null }
    fun update(now:Long,hands:List<AirOwnership.Hand>,connected:Boolean,calibration:Boolean,intervalMs:Long,confidence:Float,sensitivity:Int,legacy:Boolean=true,holdMs:Long=400,timeoutMs:Long=60_000,navigation:AirNavigation=AirNavigation.POINTING,joystickSensitivity:Int=50,apps:Set<AirApp> = emptySet(),appSupported:Boolean=false) {
        if(household) {
            if(legacyWas!=legacy || calibrationWas!=calibration) { home.reset("mode changed");legacyWas=legacy;calibrationWas=calibration }
            home.navigation=navigation;home.joystickSensitivity=joystickSensitivity;home.apps=apps;home.appSupported=appSupported
            home.timeoutMs=timeoutMs.takeIf { it in listOf(30_000L,60_000L,120_000L,300_000L) } ?: 60_000L
            home.update(now,hands,connected,calibration,confidence,sensitivity,legacy,holdMs);syncHome();return
        }
        accepted=null
        if(legacyWas!=legacy) { reset();legacyWas=legacy }
        pointing.holdMs=holdMs
        if(calibrationWas!=calibration) { reset();calibrationWas=calibration }
        gestures.minimumConfidence=confidence;ownership.minimumConfidence=confidence;gestures.sensitivity=sensitivity
        val grace=(intervalMs*3+300).coerceIn(1400,2600)
        ownership.graceMs=grace;gestures.trackingGraceMs=grace
        if(!connected && !calibration) { reset();status="Standby · waiting for TV";return }
        if(awaitingRelease) {
            if(hands.any { it.gesture=="Open_Palm" }) { status="Disarmed · relax palm before a new claim";return }
            awaitingRelease=false
        }
        val r=ownership.update(now,hands)
        if(r.hand==null && expire(now)) return
        when {
            r.lost -> { reset();status="Disarmed · ${r.reason.ifEmpty { "tracking lost / unsafe continuity" }}" }
            r.ambiguous -> { gestures.pause(now);pointing.pause();status="Tracking ambiguous · commands paused" }
            r.paused -> { gestures.pause(now);pointing.pause();status="Tracking temporarily lost / uncertain · commands paused" }
            r.claimed -> { val h=r.hand!!;gestures.claim(now,h.x,h.y);lastActivityAt=now;status="Active · hand tracked";if(!calibration) onClaim() }
            r.hand!=null -> {
                val h=r.hand
                accepted=if(legacy) gestures.update(now,h.gesture,h.confidence,h.x,h.y,h.size)
                    else pointing.update(now,h.gesture,h.confidence,confidence)
                if(!legacy && pointing.activityAt>lastActivityAt && pointing.activityStartedAt<=lastActivityAt+30_000 && now-lastActivityAt<=31_400) {
                    lastActivityAt=now;gestures.activity(now)
                }
                if(expire(now)) return
                if(accepted!=null) { gestures.accept(now);lastCommandAt=now;lastActivityAt=now }
                if(!gestures.armed) { reset();status="Disarmed" }
                else {
                    status=if(r.recovered) "Tracking recovered · Active" else "Active · ${if(legacy) gestures.movement else pointing.reason}"
                    accepted?.let { if(!calibration) send(it) }
                }
            }
            else -> status=if(ownership.progressMs>0) AirRecognitionStatus.claiming(hands.joinToString { if(it.geometricEvidence) "${it.gesture} geometry" else "${it.gesture} ${(it.confidence*100).toInt()}%" },ownership.progressMs) else "Standby"
        }
    }
    private fun expire(now:Long):Boolean {
        if(!gestures.expired(now)) return false
        reset();awaitingRelease=true;status="Disarmed · 30 seconds without intentional activity";return true
    }

}
