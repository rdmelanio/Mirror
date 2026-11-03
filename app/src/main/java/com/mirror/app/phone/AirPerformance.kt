package com.mirror.app.phone

/** Actual completed work, bounded to 64 samples; no camera data or identifiers. */
internal class AirPerformance {
    data class Sample(val frameAge:Long,val conversion:Long,val inference:Long,val decision:Long,val frameToDecision:Long=frameAge+conversion+inference+decision)
    private val samples=java.util.ArrayDeque<Sample>()
    @Synchronized fun add(value:Sample) { samples.add(value);while(samples.size>64) samples.removeFirst() }
    @Synchronized fun summary():String {
        if(samples.isEmpty()) return "No completed measurements"
        fun stat(f:(Sample)->Long):String { val v=samples.map(f).sorted();return "${v[(v.size-1)/2]}/${v[((v.size-1)*.95).toInt()]}" }
        return "p50/p95 ms · analyzer age ${stat { it.frameAge }} · RGB ${stat { it.conversion }} · model ${stat { it.inference }} · decision ${stat { it.decision }} · received-frame→decision ${stat { it.frameToDecision }} · n=${samples.size}"
    }
}
