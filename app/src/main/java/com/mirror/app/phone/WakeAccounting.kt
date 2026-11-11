package com.mirror.app.phone
/** Diagnostic accounting only; does not decide whether the camera may sleep. */
internal class WakeAccounting {
    private var acquiredAt:Long?=null
    private var accumulated=0L
    private var acquisitions=0
    @Synchronized fun update(held:Boolean,now:Long):String {
        val start=acquiredAt
        if(held && start==null) { acquiredAt=now;acquisitions++ }
        if(!held && start!=null) { accumulated+=(now-start).coerceAtLeast(0);acquiredAt=null }
        val total=accumulated+(acquiredAt?.let { (now-it).coerceAtLeast(0) } ?: 0)
        return "CPU wake lock ${if(held) "held" else "released"} · ${total}ms · acquisitions $acquisitions (service lifetime)"
    }
}
