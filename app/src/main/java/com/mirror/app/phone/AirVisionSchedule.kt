package com.mirror.app.phone

/** Keep household discovery long enough for a one-second palm, with bounded body work. */
internal class AirVisionSchedule {
    private var burstAt=-1L
    fun discover(now:Long,stage:AirStage):Boolean {
        if(stage!=AirStage.ACTIVE) return true
        if(burstAt<0 || now-burstAt>=4500) burstAt=now
        return now-burstAt<=1400
    }
    fun poseInterval(stage:AirStage,thermal:Int)=when {
        thermal>=3 -> 3000L
        thermal>=2 -> 2000L
        stage==AirStage.RECOVERY -> 500L
        stage==AirStage.ACTIVE -> 1400L
        else -> 900L
    }
}
