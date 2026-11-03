package com.mirror.app.phone

/** Opt-in on-device comparison. Nothing is synthesized; first second of each profile is warm-up. */
internal object AirBenchmark {
    data class Profile(val gpu:Boolean,val hands:Int,val size:Int,val crop:Boolean) {
        override fun toString()="${if(gpu) "GPU" else "CPU"}/$hands hands/$size/${if(crop) "crop" else "wide"}"
    }
    private val profiles=listOf(false,true).flatMap { gpu -> listOf(2,4).flatMap { hands -> listOf(480,640).flatMap { size -> listOf(false,true).map { Profile(gpu,hands,size,it) } } } }
    private var started=-1L;private var previousCalibration=false
    private val results=linkedMapOf<String,AirPerformance>()
    @Volatile var running=false;private set
    @Synchronized fun start(now:Long) { if(running) return;results.clear();started=now;previousCalibration=AirRemote.calibration;AirRemote.calibration=true;running=true }
    @Synchronized fun profile(now:Long):Profile? {
        if(!running) return null
        val i=((now-started)/6500).toInt()
        if(i>=profiles.size) { stop();return null }
        return profiles[i]
    }
    @Synchronized fun measured(now:Long,profile:Profile,actual:String,sample:AirPerformance.Sample) {
        if(running && (now-started)%6500>=1000) results.getOrPut("$profile (actual $actual)") { AirPerformance() }.add(sample)
    }
    @Synchronized fun stop() { if(running) AirRemote.calibration=previousCalibration;running=false }
    @Synchronized fun summary():String=(if(running) "Benchmark running · calibration blocks transmission\n" else "On-device benchmark results (not TV execution latency)\n")+results.entries.joinToString("\n") { "${it.key}: ${it.value.summary()}" }.ifEmpty { "Not run on this device" }
}
