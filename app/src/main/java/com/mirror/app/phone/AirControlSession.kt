package com.mirror.app.phone

/** Production boundary shared by camera inference and sequence regression tests. */
internal class AirControlSession(private val onClaim:()->Unit={},private val send:(AirGestures.Command)->Unit={}) {
    val gestures=AirGestures()
    val ownership=AirOwnership()
    var status="Standby";private set
    var accepted:AirGestures.Command?=null;private set
    private var calibrationWas=false
    fun reset() { gestures.reset();ownership.reset();status="Disarmed";accepted=null }
    fun update(now:Long,hands:List<AirOwnership.Hand>,connected:Boolean,calibration:Boolean,intervalMs:Long,confidence:Float,sensitivity:Int) {
        accepted=null
        if(calibrationWas!=calibration) { reset();calibrationWas=calibration }
        gestures.minimumConfidence=confidence;ownership.minimumConfidence=confidence;gestures.sensitivity=sensitivity
        val grace=(intervalMs*3+300).coerceIn(1400,2600)
        ownership.graceMs=grace;gestures.trackingGraceMs=grace
        if(!connected && !calibration) { reset();status="Standby · waiting for TV";return }
        if(gestures.expired(now)) { reset();status="Disarmed · 30 seconds without a command";return }
        val r=ownership.update(now,hands)
        when {
            r.lost -> { reset();status="Disarmed · tracking lost / unsafe continuity" }
            r.ambiguous -> { gestures.pause(now);status="Tracking ambiguous · commands paused" }
            r.paused -> { gestures.pause(now);status="Tracking temporarily lost · commands paused" }
            r.claimed -> { val h=r.hand!!;gestures.claim(now,h.x,h.y);status="Active · hand tracked";if(!calibration) onClaim() }
            r.hand!=null -> {
                val h=r.hand
                accepted=gestures.update(now,h.gesture,h.confidence,h.x,h.y,h.size)
                if(!gestures.armed) { reset();status="Disarmed" }
                else {
                    status=if(r.recovered) "Tracking recovered · Active" else "Active · ${gestures.movement}"
                    accepted?.let { if(!calibration) send(it) }
                }
            }
            else -> status=if(ownership.progressMs>0) AirRecognitionStatus.claiming(hands.joinToString { "${it.gesture} ${(it.confidence*100).toInt()}%" },ownership.progressMs) else "Standby"
        }
    }
}
