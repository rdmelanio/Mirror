package com.mirror.app.phone

/** Bounded FIFO of fresh keys. Connection generation changes clear it; nothing survives reconnect. */
internal class AirKeyQueue(private val capacity:Int=2,private val expiryMs:Long=400,private val event:(String,AirGestures.Command,Long)->Unit={_,_,_->}) {
    data class Key(val command:AirGestures.Command,val at:Long,val observedAt:Long=at)
    private val queue=java.util.ArrayDeque<Key>()
    var dropped=0L;private set
    var queueMs=0L;private set
    var transmitMs=0L;private set
    private var frameToQueueMs=0L
    private val frameDelays=java.util.ArrayDeque<Long>()
    private val delays=java.util.ArrayDeque<Long>()
    @Synchronized fun offer(command:AirGestures.Command,now:Long,observedAt:Long=now):Boolean {
        if(queue.size>=capacity) { dropped++;event("REJECTED",command,0);return false };frameToQueueMs=(now-observedAt).coerceAtLeast(0);queue.add(Key(command,now,observedAt));event("QUEUED",command,frameToQueueMs);return true
    }
    @Synchronized fun take(now:Long):Key? {
        while(queue.isNotEmpty()) {
            val key=queue.removeFirst();queueMs=(now-key.at).coerceAtLeast(0)
            if(queueMs<=expiryMs) return key
            dropped++;event("EXPIRED",key.command,queueMs)
        }
        return null
    }
    @Synchronized fun clear() { dropped+=queue.size;queue.forEach { event("DISCARDED",it.command,0) };queue.clear() }
    @Synchronized fun sent(key:Key,end:Long,duration:Long) {
        frameDelays.add((end-key.observedAt).coerceAtLeast(0));while(frameDelays.size>64) frameDelays.removeFirst();transmitMs=duration.coerceAtLeast(0);event("TRANSMITTED",key.command,(end-key.at).coerceAtLeast(0));delays.add((end-key.at).coerceAtLeast(0));while(delays.size>64) delays.removeFirst()
    }
    @Synchronized fun summary():String {
        val sorted=delays.sorted();fun percentile(p:Double)=if(sorted.isEmpty()) "n/a" else sorted[((sorted.size-1)*p).toInt()].toString()
        val frames=frameDelays.sorted();val frameSummary=if(frames.isEmpty()) "n/a" else "${frames[(frames.size-1)/2]}/${frames[((frames.size-1)*.95).toInt()]}"
        return "frame→queue ${frameToQueueMs}ms · queue ${queueMs}ms · write ${transmitMs}ms · dropped $dropped · queue→socket p50 ${percentile(.5)} / p95 ${percentile(.95)}ms · received-frame→write p50/p95 $frameSummary ms"
    }
}
