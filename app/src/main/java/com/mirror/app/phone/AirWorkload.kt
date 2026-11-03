package com.mirror.app.phone

internal enum class AirStage { ECO, PRE_ACTIVATION, ACTIVE, RECOVERY }

/** Thermal downgrade is immediate; recovery requires one minute at a lower reported level. */
internal class AirWorkload {
    var stage=AirStage.ECO;private set
    var thermal=0;private set
    private var lowerSince=-1L
    private var palmAt=-1L
    fun thermal(now:Long,reported:Int) {
        if(reported>=thermal) { thermal=reported;lowerSince=-1 }
        else { if(lowerSince<0) lowerSince=now; if(now-lowerSince>=60_000) { thermal=reported;lowerSince=-1 } }
    }
    fun observe(now:Long,crediblePalm:Boolean,claimed:Boolean,recovery:Boolean=false) {
        if(crediblePalm) palmAt=now
        stage=when { recovery -> AirStage.RECOVERY; claimed -> AirStage.ACTIVE; palmAt>=0 && now-palmAt<=900 -> AirStage.PRE_ACTIVATION;else -> AirStage.ECO }
    }
    fun interval(processingMs:Double=0.0):Long {
        val base=when(stage) { AirStage.ECO -> 400L;AirStage.PRE_ACTIVATION,AirStage.RECOVERY -> 100L;AirStage.ACTIVE -> 50L }
        val thermalFloor=when { thermal>=4 -> if(stage==AirStage.ECO) 1500L else 750L;thermal>=3 -> if(stage==AirStage.ECO) 1000L else 333L;thermal>=2 -> if(stage==AirStage.ECO) 500L else 150L;else -> 0L }
        val thermalHeadroom=if(thermal>=2) (processingMs*(1.5+(thermal-2).coerceAtMost(4)*.5)).toLong() else 0L
        return maxOf(base,thermalFloor,thermalHeadroom)
    }
    fun reset() { stage=AirStage.ECO;palmAt=-1 }
    companion object {
        fun encodeJpeg(airEnabled:Boolean,viewers:Boolean)=!airEnabled || viewers
        fun wifiPerformance(viewers:Boolean)=viewers
        fun fpsRange(ranges:List<Pair<Int,Int>>,eco:Boolean):Pair<Int,Int>? =
            if(eco) ranges.filter { it.first>0 && it.second>=2 }.minWithOrNull(compareBy<Pair<Int,Int>> { it.second }.thenBy { it.first })
            else ranges.filter { it.second==30 }.maxByOrNull { it.first }
                ?: ranges.filter { it.second in 24..30 }.maxByOrNull { it.first }
                ?: ranges.filter { it.second>=30 }.minByOrNull { it.second }
    }
}

/** One reusable lease; there is no pending frame queue. A busy lease discards incoming camera frames. */
internal class AirFrameGate {
    private val busy=java.util.concurrent.atomic.AtomicBoolean()
    fun acquire()=busy.compareAndSet(false,true)
    fun release() { busy.set(false) }
    fun available()=!busy.get()
}
