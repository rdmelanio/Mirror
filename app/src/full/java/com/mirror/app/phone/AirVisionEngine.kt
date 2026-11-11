package com.mirror.app.phone

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.hypot

internal interface AirVisionEngine:AutoCloseable {
    data class Hand(val points:List<AirPointing.Point>,val name:String,val score:Float,val body:Int?=null,val shoulder:AirPointing.Point?=null,val geometricEvidence:Boolean=false)
    data class Result(val hands:List<Hand>,val diagnostic:String,val poseMs:Long=0,val handMs:Long=0,val conversionMs:Long=0,val persons:Int=0)
    fun recognize(raw:AirRawFrame,now:Long,roi:AirRoi,maxSide:Int,inputMode:String,stage:AirStage,thermal:Int,owner:AirOwnership.Hand?,mirrored:Boolean,wideInput:Boolean):Result
}

internal class AirGestureEngine(context:Context,gpu:Boolean,hands:Int,private val input:AirInputPolicy):AirVisionEngine {
    private val model=GestureRecognizer.createFromOptions(context,GestureRecognizer.GestureRecognizerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").setDelegate(if(gpu) Delegate.GPU else Delegate.CPU).build())
        // IMAGE also makes compatibility crops safe when their coordinate space changes.
        .setRunningMode(RunningMode.IMAGE).setNumHands(hands)
        .setMinHandDetectionConfidence(.65f).setMinHandPresenceConfidence(.65f).setMinTrackingConfidence(.65f).build())
    override fun recognize(raw:AirRawFrame,now:Long,roi:AirRoi,maxSide:Int,inputMode:String,stage:AirStage,thermal:Int,owner:AirOwnership.Hand?,mirrored:Boolean,wideInput:Boolean):AirVisionEngine.Result {
        val start=SystemClock.elapsedRealtime();var inference=start
        val r=input.process(inputMode,{ bitmap -> raw.image(maxSide,bitmap,roi) }) { image -> inference=SystemClock.elapsedRealtime();model.recognize(image) }
        return AirVisionEngine.Result(r.landmarks().mapIndexed { i,p ->
            val c=r.gestures().getOrNull(i)?.maxByOrNull { it.score() }
            AirVisionEngine.Hand(p.map { roi.full(AirPointing.Point(it.x(),it.y())) },c?.categoryName().orEmpty(),c?.score() ?: 0f)
        },"Compatibility Gesture Recognizer",handMs=SystemClock.elapsedRealtime()-inference,conversionMs=inference-start)
    }
    override fun close()=model.close()
}

/** One worker owns both tasks. Pose is decimated; IMAGE hand crops never share temporal model state. */
internal class AirPoseEngine(context:Context,gpu:Boolean,persons:Int,private val input:AirInputPolicy):AirVisionEngine {
    private var pose:PoseLandmarker?=null
    private var hand:HandLandmarker?=null
    private val regions=AirBodyRegions()
    private var poseAt=-1L;private var scanAt=-1L
    private val schedule=AirVisionSchedule()
    private var cropMemory:AirRoi?=null
    private var previous=emptyList<AirVisionEngine.Hand>();private var previousAt=-1L
    init {
        try {
            pose=PoseLandmarker.createFromOptions(context,PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(if(gpu) Delegate.GPU else Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(persons.coerceIn(1,2))
                .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
            hand=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(if(gpu) Delegate.GPU else Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setNumHands(2)
                .setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.6f).setMinTrackingConfidence(.5f).build())
        } catch(t:Throwable) { close();throw t }
    }
    override fun recognize(raw:AirRawFrame,now:Long,roi:AirRoi,maxSide:Int,inputMode:String,stage:AirStage,thermal:Int,owner:AirOwnership.Hand?,mirrored:Boolean,wideInput:Boolean):AirVisionEngine.Result {
        var poseMs=0L;var handMs=0L;var conversion=0L
        val aspect=raw.uprightWidth.toFloat()/raw.uprightHeight.coerceAtLeast(1)
        val bodyInterval=schedule.poseInterval(stage,thermal)
        if(poseAt<0 || now-poseAt>=bodyInterval) {
            val start=SystemClock.elapsedRealtime();var inference=start
            val result=input.process(inputMode,{ bitmap -> raw.image(256,bitmap) }) { image -> inference=SystemClock.elapsedRealtime();pose!!.detect(image) }
            conversion+=inference-start;poseMs=SystemClock.elapsedRealtime()-inference;poseAt=now
            regions.update(now,result.landmarks().map { body -> body.map { AirBodyRegions.Joint(it.x(),it.y(),minOf(it.visibility().orElse(0f),it.presence().orElse(1f))) } })
        }
        val guided=regions.regions(now,aspect)
        val recent=if(now-previousAt<=1800) previous else emptyList()
        val owned=owner?.let { o -> recent.minByOrNull { hypot((if(mirrored) 1-it.points[0].x else it.points[0].x)-o.x,it.points[0].y-o.y) } }
        val candidates=mutableListOf<AirBodyRegions.Region>()
        if(owned!=null) {
            val target=AirBodyRegions.refine(owned.points,aspect)
            val old=cropMemory
            val stable=if(old==null) target else AirRoi(old.left*.25f+target.left*.75f,old.top*.25f+target.top*.75f,old.right*.25f+target.right*.75f,old.bottom*.25f+target.bottom*.75f)
            cropMemory=stable
            val expansion=1f+((now-previousAt).coerceIn(0,1500)/1500f)*.7f
            candidates.add(AirBodyRegions.Region(AirBodyRegions.box((stable.left+stable.right)/2,(stable.top+stable.bottom)/2,stable.width*expansion,stable.height*expansion),owned.body,owned.shoulder))
        }
        else recent.forEach { candidates.add(AirBodyRegions.Region(AirBodyRegions.refine(it.points,aspect),it.body,it.shoulder)) }
        candidates.addAll(guided.sortedBy { if(it.body==owner?.bodyId) 0 else 1 })
        val discovery=schedule.discover(now,stage)
        val wide=wideInput || scanAt<0 || now-scanAt>=4500 || candidates.isEmpty()
        // Alternate body wrists during discovery so an owned crop cannot starve the other person.
        val background=guided.filter { r -> owned==null || r.body!=owned.body }
        val selected=if(wide) listOf(AirBodyRegions.Region(AirRoi.FULL,null,null))
            else if(discovery && background.isNotEmpty()) (candidates.take(1)+background.drop(((now/200)%background.size).toInt()).take(1)).distinctBy { it.roi }
            else candidates.distinctBy { it.roi }.take(if(thermal>=3 || stage==AirStage.ACTIVE) 1 else 2)
        if(selected.any { it.roi==AirRoi.FULL }) scanAt=now
        val found=mutableListOf<AirVisionEngine.Hand>()
        for(region in selected) {
            val start=SystemClock.elapsedRealtime();var inference=start
            val result=input.process(inputMode,{ bitmap -> raw.image(if(region.roi==AirRoi.FULL) maxSide else 384,bitmap,region.roi) }) { image -> inference=SystemClock.elapsedRealtime();hand!!.detect(image) }
            conversion+=inference-start;handMs+=SystemClock.elapsedRealtime()-inference
            val earlierCrops=found.toList()
            result.landmarks().forEach { p ->
                val full=p.map { region.roi.full(AirPointing.Point(it.x(),it.y())) }
                // Never collapse separate detections from this model call, or different bodies.
                if(region.body!=null && earlierCrops.any { it.body==region.body && AirHandGeometry.sameObservation(it.points,full) }) return@forEach
                val geometry=AirHandGeometry.classify(full.map { AirPointing.Point(it.x*aspect,it.y) })
                val matched=guided.filter { r -> full[0].x in r.roi.left..r.roi.right && full[0].y in r.roi.top..r.roi.bottom }.mapNotNull { it.body }.distinct().singleOrNull()
                val association=guided.firstOrNull { it.body==matched }
                found.add(AirVisionEngine.Hand(full,geometry.name,geometry.evidence,matched ?: region.body.takeIf { id -> regions.bodies.any { it.id==id && now-it.at<=2400 } },association?.shoulder ?: region.shoulder.takeIf { regions.bodies.any { it.id==region.body && now-it.at<=2400 } },geometricEvidence=true))
            }
        }
        if(found.isNotEmpty()) { previous=found;previousAt=now }
        val source=selected.joinToString { "${(it.roi.width*raw.uprightWidth).toInt()}x${(it.roi.height*raw.uprightHeight).toInt()}" }
        val state=when { found.any { it.name!="None" } -> "Gesture recognized · temporal evidence pending";found.isNotEmpty() -> "Hand detected, gesture uncertain";guided.isNotEmpty() -> "Wrist predicted, hand not detected in crop";regions.bodies.isNotEmpty() -> "Person detected, wrist unavailable";else -> "No person detected · fallback hand scan" }
        return AirVisionEngine.Result(found,"$state · source crops $source · bodies ${regions.bodies.size} wrists ${guided.size} hands ${found.size}",poseMs,handMs,conversion,regions.bodies.size)
    }
    override fun close() { try { hand?.close() } finally { hand=null;pose?.close();pose=null } }
}
