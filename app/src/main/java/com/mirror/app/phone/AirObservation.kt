package com.mirror.app.phone

import kotlin.math.hypot

/** Shared production landmark → pose/physical-hand boundary. Coordinates are full-camera upright before mirroring. */
internal object AirObservation {
    fun hand(points:List<AirPointing.Point>,native:String,score:Float,mirrored:Boolean,legacy:Boolean,threshold:Float,geometricEvidence:Boolean=false):AirOwnership.Hand {
        require(points.size==21 && points.all { it.x.isFinite() && it.y.isFinite() })
        val palm=listOf(0,5,9,13,17).map { points[it] }
        val rawX=palm.map { it.x }.average().toFloat()
        val pose=if(legacy) native else AirShortcutPose.classify(points) ?: AirPointing.resolve(points,mirrored,native,score,threshold)
        val index=(AirPointing.indexPose(points) || pose=="Pointing_Up") && pose !in setOf("Thumb_Up","Thumb_Down","Victory","SHAKA","THREE_FINGERS","Closed_Fist","Open_Palm")
        return AirOwnership.Hand(if(mirrored) 1-rawX else rawX,palm.map { it.y }.average().toFloat(),hypot(points[0].x-points[9].x,points[0].y-points[9].y),pose,score,index,geometricEvidence=geometricEvidence)
    }
}
