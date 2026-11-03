package com.mirror.app.phone

import kotlin.math.*

/** Position/scale association only; never an identity or a biometric identifier. */
internal class AirOwnership {
    private var velocityX=0f;private var velocityY=0f
    var claimDurationMs=2000L
    var claimSamples=5
    var minimumHandSize=.02f
    var graceMs=1400L
    private var missing=false
    var minimumConfidence = .65f
    var progressMs = 0L; private set
    data class Hand(val x: Float, val y: Float, val size: Float, val gesture: String, val confidence: Float)
    data class Selection(val hand: Hand?, val claimed: Boolean = false, val lost: Boolean = false, val ambiguous: Boolean = false, val paused: Boolean = false, val recovered: Boolean = false, val reason:String="")
    private data class Candidate(var hand: Hand, var since: Long, var anchorX: Float, var anchorY: Float, val evidence: MutableList<Pair<Long, Boolean>> = mutableListOf(), var lastGood: Long = since)
    private var candidates = mutableListOf<Candidate>()
    private var owner: Hand? = null
    private var lastSeen = -1L
    private var ambiguousSince = -1L
    var claimed = false; private set
    fun reset() { velocityX=0f;velocityY=0f;missing=false;progressMs=0; candidates.clear(); owner = null; claimed = false; lastSeen = -1; ambiguousSince = -1 }
    private fun distance(a: Hand, b: Hand) = hypot(a.x - b.x, a.y - b.y)
    private fun predicted(before:Hand,gap:Long):Hand {
        val dx=velocityX*(gap/1000f);val dy=velocityY*(gap/1000f)
        // Extrapolation is only a local tie-breaker, never an expanding search for a new hand.
        val scale=minOf(1f,.10f/hypot(dx,dy).coerceAtLeast(.0001f))
        return Hand(before.x+dx*scale,before.y+dy*scale,before.size,"",0f)
    }
    private fun matches(a: Hand, b: Hand, seconds: Float): Boolean =
        distance(a,b) <= minOf(.32f, .08f + a.size * .5f + seconds * 1.2f) &&
            b.size / a.size.coerceAtLeast(.02f) in .2f..3.5f
    fun update(now: Long, hands: List<Hand>): Selection {
        val valid = hands.filter { it.x.isFinite() && it.y.isFinite() && it.size.isFinite() && it.size >= minimumHandSize }
        val gap = if (lastSeen < 0) 0 else now-lastSeen
        if (claimed) {
            if (gap > graceMs) { reset(); return Selection(null,lost=true,reason="tracking gap exceeded ${graceMs} ms") }
            val before = owner!!
            val nearby = valid.filter { matches(before,it,gap/1000f) }
            if (nearby.isEmpty()) {
                if(valid.isNotEmpty()) {
                    // Size/centroid outliers near the previous trajectory are uncertain, not new owners.
                    val predicted=predicted(before,gap)
                    val plausible=valid.singleOrNull()?.let { distance(before,it)<=.20f && distance(predicted,it)<=.16f }
                    if(plausible!=true) { reset();return Selection(null,lost=true,reason="unmatched hand outside continuity corridor") }
                }
                missing=true;return Selection(null,paused=true)
            }
            // Nearby crossing hands cannot be assigned safely. Freeze until separated; never reassign on loss.
            if (nearby.size != 1 || valid.any { it !== nearby[0] && distance(it,nearby[0]) < .12f }) {
                if (ambiguousSince < 0) ambiguousSince = now
                if (now-ambiguousSince > 600) { reset(); return Selection(null,lost=true,reason="multiple plausible hands persisted") }
                return Selection(null,ambiguous=true)
            }
            if (ambiguousSince >= 0) { reset(); return Selection(null,lost=true,reason="identity continuity ambiguous; fresh claim required") }
            val recovered=missing;missing=false
            if(recovered && (distance(before,nearby[0])>.20f ||
                distance(predicted(before,gap),nearby[0])>.16f)) {
                reset();return Selection(null,lost=true,reason="reappearance outside tracked trajectory")
            }
            if(gap>0 && !recovered) {
                velocityX=(velocityX*.5f+(nearby[0].x-before.x)/(gap/1000f)*.5f).coerceIn(-.8f,.8f)
                velocityY=(velocityY*.5f+(nearby[0].y-before.y)/(gap/1000f)*.5f).coerceIn(-.8f,.8f)
            } else if(recovered) { velocityX=0f;velocityY=0f }
            owner = nearby[0]; lastSeen = now
            return Selection(owner,recovered=recovered)
        }
        if (gap > graceMs) candidates.clear()
        val palms = valid // Associate landmarks even when the classifier briefly loses the palm label.
        val next = mutableListOf<Candidate>()
        for (hand in palms) {
            val possible = candidates.filter { matches(it.hand,hand,(gap/1000f).coerceAtLeast(.1f)) }
            val old = possible.singleOrNull()?.takeIf { c -> palms.count { matches(c.hand,it,(gap/1000f).coerceAtLeast(.1f)) } == 1 }
            if (valid.any { it !== hand && distance(it,hand) < .12f }) continue
            val good = hand.gesture == "Open_Palm" && hand.confidence >= minimumConfidence
            if (old == null && !good) continue
            val c = old ?: Candidate(hand,now,hand.x,hand.y)
            if (hypot(hand.x-c.anchorX,hand.y-c.anchorY) > .07f) { c.since=now;c.anchorX=hand.x;c.anchorY=hand.y;c.evidence.clear() }
            if(good) c.lastGood=now
            if(now-c.lastGood>650) continue
            c.evidence.add(now to good)
            c.evidence.removeAll { now-it.first>maxOf(2400L,graceMs*2) }
            c.hand=hand;next.add(c)
        }
        candidates=next;lastSeen=now
        progressMs=next.maxOfOrNull { (now-it.since).coerceAtMost(claimDurationMs) } ?: 0
        val winners = next.filter { now-it.since >= claimDurationMs && now-it.lastGood<=400 &&
            it.evidence.size>=claimSamples && it.evidence.count { e -> e.second } >= (claimSamples-1).coerceAtLeast(3) &&
            it.evidence.count { e -> e.second }.toFloat()/it.evidence.size >= .65f }.sortedBy { it.since }
        if (winners.isNotEmpty() && (winners.size==1 || winners[0].since < winners[1].since)) {
            owner=winners[0].hand;claimed=true;candidates.clear()
            return Selection(owner,claimed=true)
        }
        return Selection(null,ambiguous=winners.size>1)
    }
}

/** Sampling interval includes recovery headroom; one inference may be in flight. */
internal class AirInferenceRate {
    var latencyMs = 0.0; private set
    fun measured(ms: Long) { latencyMs = if(latencyMs==0.0) ms.toDouble() else latencyMs*.8+ms*.2 }
    // Latency is measured for diagnostics; one in-flight frame supplies throughput backpressure.
    fun interval(armed: Boolean): Long = if(armed) 50L else 400L
}
