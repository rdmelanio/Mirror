package com.mirror.app.phone

/** Monotonic timestamps from a single camera epoch; startup has a longer grace than frame stalls. */
internal class CameraEpochWatchdog {
    private var startedAt: Long? = null
    private var frameAt: Long? = null
    private var generation=0
    @Synchronized fun start(now:Long,epoch:Int=0) { generation=epoch;startedAt=now;frameAt=null }
    @Synchronized fun stop() { startedAt=null;frameAt=null }
    @Synchronized fun frame(now:Long,epoch:Int=0) { if(startedAt!=null && epoch==generation) frameAt=now }
    @Synchronized fun stalled(now:Long):Boolean {
        val start=startedAt ?: return false
        val frame=frameAt
        return if(frame==null) now-start>20_000 else now-frame>8_000
    }
}
