package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class AirEcoPointTest {
    private fun pointing(rotation:Int=0):List<AirPointing.Point> {
        // Wrist, index extended, other fingers folded, thumb tucked. Rotate whole hand around wrist.
        val p=MutableList(21) { AirPointing.Point(.51f,.57f) }
        p[0]=AirPointing.Point(.5f,.7f);p[5]=AirPointing.Point(.5f,.5f);p[6]=AirPointing.Point(.5f,.4f);p[7]=AirPointing.Point(.5f,.3f);p[8]=AirPointing.Point(.5f,.2f)
        p[9]=AirPointing.Point(.53f,.5f);p[10]=AirPointing.Point(.53f,.44f);p[12]=AirPointing.Point(.53f,.58f)
        p[13]=AirPointing.Point(.56f,.51f);p[14]=AirPointing.Point(.56f,.46f);p[16]=AirPointing.Point(.56f,.59f)
        p[17]=AirPointing.Point(.59f,.53f);p[18]=AirPointing.Point(.59f,.48f);p[20]=AirPointing.Point(.59f,.6f)
        p[2]=AirPointing.Point(.47f,.6f);p[3]=AirPointing.Point(.48f,.57f);p[4]=AirPointing.Point(.51f,.54f)
        val a=Math.toRadians(rotation.toDouble());return p.map { v ->
            val x=v.x-.5;val y=v.y-.7
            AirPointing.Point((.5+x*kotlin.math.cos(a)-y*kotlin.math.sin(a)).toFloat(),(.7+x*kotlin.math.sin(a)+y*kotlin.math.cos(a)).toFloat())
        }
    }
    @Test fun productionLandmarksResolveFourDirectionsMirrorsAndRejectDiagonalsOrExtraFinger() {
        for((angle,name) in listOf(0 to "POINT_UP",90 to "POINT_RIGHT",180 to "POINT_DOWN",270 to "POINT_LEFT")) {
            assertEquals(name,AirPointing.classify(pointing(angle),false))
            val mirrored=when(name) { "POINT_LEFT" -> "POINT_RIGHT";"POINT_RIGHT" -> "POINT_LEFT";else -> name }
            assertEquals(mirrored,AirPointing.classify(pointing(angle),true))
        }
        assertNull(AirPointing.classify(pointing(45),false))
        val p=pointing().toMutableList();p[12]=AirPointing.Point(.53f,.2f)
        assertNull(AirPointing.classify(p,false));assertNull(AirPointing.classify(emptyList(),false))
    }
    @Test fun oneActivationSupportsEntireOptionASequenceAtSlowIrregularRatesAndCalibration() {
        for(calibration in listOf(false,true)) for(step in listOf(83L,333L,480L)) {
            val sent=mutableListOf<AirGestures.Command>();var claims=0;var now=0L
            val s=AirControlSession({claims++},{sent.add(it)})
            val accepted=mutableListOf<AirGestures.Command>()
            fun frame(name:String,size:Float=.1f) {
                now+=step+if(now%3==0L) 20 else 0
                s.update(now,listOf(AirOwnership.Hand(.5f,.5f,size,name,.95f)),true,calibration,step,.65f,50,false,400)
                s.accepted?.let { accepted.add(it) }
            }
            fun hold(name:String,ms:Long) { val until=now+ms;while(now<until) frame(name) }
            hold("Open_Palm",2800);assertTrue(s.gestures.armed);assertTrue(accepted.isEmpty())
            for(name in listOf("POINT_UP","POINT_UP","POINT_RIGHT","Thumb_Up","Thumb_Down","Victory")) {
                hold(name,1400);hold("Closed_Fist",850)
            }
            assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.UP,AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),accepted)
            if(calibration) { assertTrue(sent.isEmpty());assertEquals(0,claims) } else { assertEquals(accepted,sent);assertEquals(1,claims) }
            hold("Closed_Fist",31_000);assertFalse(s.gestures.armed)
        }
    }
    @Test fun pointingConfidenceDwellAndHomeSafety() {
        val p=AirPointCommands();assertNull(p.update(0,"Pointing_Up",.5f,.65f));assertNull(p.update(500,"Pointing_Up",.5f,.65f))
        assertNull(p.update(1000,"Victory",.9f,.65f));assertNull(p.update(1500,"Victory",.9f,.65f))
        assertEquals(AirGestures.Command.HOME,p.update(1800,"Victory",.9f,.65f));assertNull(p.update(3000,"Victory",.9f,.65f))
    }
    @Test fun workloadStagesThroughputAndThermalRecovery() {
        val w=AirWorkload();assertEquals(400L,w.interval())
        w.observe(0,true,false);assertEquals(AirStage.PRE_ACTIVATION,w.stage);assertEquals(100L,w.interval())
        w.observe(600,false,false);assertEquals(AirStage.PRE_ACTIVATION,w.stage)
        w.observe(2000,true,true);assertEquals(50L,w.interval())
        for(latency in listOf(50L,100L,200L,250L,300L)) {
            val rate=AirInferenceRate();rate.measured(latency);assertEquals(50L,rate.interval(true))
            // Real completion plus next available camera frame, without latency * 1.35 scheduling.
            assertTrue(maxOf(w.interval(),latency)<=latency+50)
        }
        w.thermal(2500,2);assertTrue(w.interval(250.0)>=375)
        w.thermal(3000,3);assertEquals(333L,w.interval());assertTrue(w.interval(250.0)>=500);w.thermal(4000,0);w.thermal(63_999,0);assertEquals(3,w.thermal)
        w.thermal(64_000,0);assertEquals(0,w.thermal)
        w.observe(40_000,false,false);assertEquals(AirStage.ECO,w.stage)
    }
    @Test fun snapshotLeaseIsBoundedReleasedAfterErrorAndEncodingOnlyFollowsStreamDemand() {
        val g=AirFrameGate();assertTrue(g.acquire());repeat(1000) { assertFalse(g.acquire()) }
        try { throw IllegalArgumentException("bad frame") } catch(_:Exception) {} finally { g.release() }
        assertTrue(g.acquire());g.release()
        assertFalse(AirWorkload.encodeJpeg(true,false));assertTrue(AirWorkload.encodeJpeg(true,true));assertTrue(AirWorkload.encodeJpeg(false,false))
        assertFalse(AirWorkload.wifiPerformance(false));assertTrue(AirWorkload.wifiPerformance(true))
        val ranges=listOf(15 to 15,15 to 30,30 to 30)
        assertEquals(15 to 15,AirWorkload.fpsRange(ranges,true));assertEquals(30 to 30,AirWorkload.fpsRange(ranges,false));assertNull(AirWorkload.fpsRange(emptyList(),true))
    }
    @Test fun directYuvConversionRotationAndInputSizes() {
        val frame=byteArrayOf(16,60,100,127,(-128).toByte(),(-128).toByte())
        val base=IntArray(4);AirPixels.convert(frame,2,2,0,2,2,base)
        assertEquals(0xff000000.toInt(),base[0])
        for(angle in listOf(90,180,270)) {
            val out=IntArray(4);AirPixels.convert(frame,2,2,angle,2,2,out)
            val expected=when(angle) { 90 -> intArrayOf(base[2],base[0],base[3],base[1]);180 -> base.reversedArray();else -> intArrayOf(base[1],base[3],base[0],base[2]) }
            assertArrayEquals(expected,out)
        }
        assertEquals(640 to 360,AirPixels.dimensions(1920,1080,0,640));assertEquals(270 to 480,AirPixels.dimensions(1920,1080,90,480))
        val input=ByteArray(1280*720*3/2) { 128.toByte() }
        for(size in listOf(480,640)) {
            val (w,h)=AirPixels.dimensions(1280,720,0,size);val out=IntArray(w*h)
            repeat(10) { AirPixels.convert(input,1280,720,0,w,h,out) }
            val start=System.nanoTime();repeat(20) { AirPixels.convert(input,1280,720,0,w,h,out) }
            println("Cloud JVM YUV conversion ${w}x$h: ${(System.nanoTime()-start)/20/1_000_000.0} ms; not an Android inference benchmark")
        }
    }
    @Test fun actualSessionDrivesPreActivationActiveAndEcoAfterIdleWithoutReclaimingHeldPalm() {
        val s=AirControlSession();val w=AirWorkload();var now=0L
        fun frame(name:String) {
            now+=400
            s.update(now,listOf(AirOwnership.Hand(.5f,.5f,.1f,name,.9f)),true,false,w.interval(),.65f,50,false)
            if(s.awaitingRelease) w.reset()
            w.observe(now,name=="Open_Palm" && !s.awaitingRelease,s.gestures.armed && s.ownership.claimed)
        }
        frame("Open_Palm");assertEquals(AirStage.PRE_ACTIVATION,w.stage)
        repeat(6) { frame("Open_Palm") };assertEquals(AirStage.ACTIVE,w.stage)
        repeat(85) { frame("Open_Palm") }
        assertEquals(AirStage.ECO,w.stage);assertFalse(s.gestures.armed);assertTrue(s.awaitingRelease)
        frame("Closed_Fist");repeat(7) { frame("Open_Palm") }
        assertEquals(AirStage.ACTIVE,w.stage)
    }

    @Test fun directOptionATransitionsOnlyNeedNeutralForSameDirectionRepeat() {
        var now=0L;var claims=0;val sent=mutableListOf<AirGestures.Command>()
        val s=AirControlSession({claims++},{sent.add(it)})
        fun hold(name:String,ms:Long) {
            val until=now+ms
            while(now<until) {
                now+=333
                s.update(now,listOf(AirOwnership.Hand(.5f,.5f,.1f,name,.95f)),true,false,333,.65f,50,false,400)
            }
        }
        hold("Open_Palm",2700);hold("POINT_UP",1400);hold("Closed_Fist",800)
        hold("POINT_UP",1400);hold("POINT_RIGHT",1400);hold("Thumb_Up",1400);hold("Thumb_Down",1400);hold("Victory",1800)
        assertEquals(listOf(AirGestures.Command.UP,AirGestures.Command.UP,AirGestures.Command.RIGHT,AirGestures.Command.OK,AirGestures.Command.BACK,AirGestures.Command.HOME),sent)
        assertEquals(1,claims);assertTrue(s.gestures.armed)
    }

}
