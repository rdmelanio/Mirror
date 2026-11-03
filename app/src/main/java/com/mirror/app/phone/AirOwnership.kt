package com.mirror.app.phone

import kotlin.math.*

/** Position/scale association only; never an identity or a biometric identifier. */
internal class AirOwnership {
    var graceMs=1400L
    private var missing=false
    var minimumConfidence = .65f
    var progressMs = 0L; private set
    data class Hand(val x: Float, val y: Float, val size: Float, val gesture: String, val confidence: Float)
    data class Selection(val hand: Hand?, val claimed: Boolean = false, val lost: Boolean = false, val ambiguous: Boolean = false, val paused: Boolean = false, val recovered: Boolean = false)
    private data class Candidate(var hand: Hand, var since: Long, var anchorX: Float, var anchorY: Float, val evidence: MutableList<Pair<Long, Boolean>> = mutableListOf(), var lastGood: Long = since)
    private var candidates = mutableListOf<Candidate>()
    private var owner: Hand? = null
    private var lastSeen = -1L
    private var ambiguousSince = -1L
    var claimed = false; private set
    fun reset() { missing=false;progressMs=0; candidates.clear(); owner = null; claimed = false; lastSeen = -1; ambiguousSince = -1 }
    private fun distance(a: Hand, b: Hand) = hypot(a.x - b.x, a.y - b.y)
    private fun matches(a: Hand, b: Hand, seconds: Float): Boolean =
        distance(a,b) <= minOf(.32f, .08f + a.size * .5f + seconds * 1.2f) &&
            b.size / a.size.coerceAtLeast(.02f) in .2f..3.5f
    fun update(now: Long, hands: List<Hand>): Selection {
        val valid = hands.filter { it.x.isFinite() && it.y.isFinite() && it.size.isFinite() && it.size >= .02f }
        val gap = if (lastSeen < 0) 0 else now-lastSeen
        if (claimed) {
            if (gap > graceMs) { reset(); return Selection(null,lost=true) }
            val before = owner!!
            val nearby = valid.filter { matches(before,it,gap/1000f) }
            if (nearby.isEmpty()) {
                if(valid.isNotEmpty()) { reset();return Selection(null,lost=true) }
                missing=true;return Selection(null,paused=true)
            }
            // Nearby crossing hands cannot be assigned safely. Freeze until separated; never reassign on loss.
            if (nearby.size != 1 || valid.any { it !== nearby[0] && distance(it,nearby[0]) < .12f }) {
                if (ambiguousSince < 0) ambiguousSince = now
                if (now-ambiguousSince > 600) { reset(); return Selection(null,lost=true) }
                return Selection(null,ambiguous=true)
            }
            if (ambiguousSince >= 0) { reset(); return Selection(null,lost=true) }
            val recovered=missing;missing=false
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
            c.evidence.removeAll { now-it.first>2400 }
            c.hand=hand;next.add(c)
        }
        candidates=next;lastSeen=now
        progressMs=next.maxOfOrNull { (now-it.since).coerceAtMost(2000) } ?: 0
        val winners = next.filter { now-it.since >= 2000 && now-it.lastGood<=400 &&
            it.evidence.size>=5 && it.evidence.count { e -> e.second } >= 4 &&
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
    fun interval(armed: Boolean): Long = maxOf(if(armed) 83L else 333L, (latencyMs*1.35).toLong())
}
