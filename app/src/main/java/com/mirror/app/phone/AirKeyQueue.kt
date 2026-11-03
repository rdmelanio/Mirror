package com.mirror.app.phone

/** Bounded FIFO of fresh keys. Connection generation changes clear it; nothing survives reconnect. */
internal class AirKeyQueue(private val capacity:Int=2,private val expiryMs:Long=400) {
    data class Key(val command:AirGestures.Command,val at:Long)
    private val queue=java.util.ArrayDeque<Key>()
    var dropped=0L;private set
    var queueMs=0L;private set
    var transmitMs=0L;private set
    private val delays=java.util.ArrayDeque<Long>()
    @Synchronized fun offer(command:AirGestures.Command,now:Long):Boolean {
        if(queue.size>=capacity) { dropped++;return false };queue.add(Key(command,now));return true
    }
    @Synchronized fun take(now:Long):Key? {
        while(queue.isNotEmpty()) {
            val key=queue.removeFirst();queueMs=(now-key.at).coerceAtLeast(0)
            if(queueMs<=expiryMs) return key
            dropped++
        }
        return null
    }
    @Synchronized fun clear() { dropped+=queue.size;queue.clear() }
    @Synchronized fun sent(key:Key,end:Long,duration:Long) {
        transmitMs=duration.coerceAtLeast(0);delays.add((end-key.at).coerceAtLeast(0));while(delays.size>64) delays.removeFirst()
    }
    @Synchronized fun summary():String {
        val sorted=delays.sorted();fun percentile(p:Double)=if(sorted.isEmpty()) "n/a" else sorted[((sorted.size-1)*p).toInt()].toString()
        return "queue ${queueMs}ms · write ${transmitMs}ms · dropped $dropped · accepted→write p50 ${percentile(.5)} / p95 ${percentile(.95)}ms"
    }
}
