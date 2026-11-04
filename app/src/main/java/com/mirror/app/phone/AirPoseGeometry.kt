package com.mirror.app.phone

import kotlin.math.*

/** Geometry evidence, not model probability. Works in upright source-pixel aspect ratio. */
internal object AirHandGeometry {
    data class Pose(val name:String,val evidence:Float)
    fun sameObservation(a:List<AirPointing.Point>,b:List<AirPointing.Point>):Boolean =
        a.size==21 && b.size==21 && a.indices.all { hypot(a[it].x-b[it].x,a[it].y-b[it].y)<.008f }
    fun classify(p:List<AirPointing.Point>):Pose {
        if(p.size!=21 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return Pose("None",0f)
        fun d(a:Int,b:Int)=hypot(p[a].x-p[b].x,p[a].y-p[b].y)
        val size=d(0,9);if(size<.003f) return Pose("None",0f)
        fun straight(m:Int,j:Int,t:Int):Boolean {
            val ax=p[j].x-p[m].x;val ay=p[j].y-p[m].y
            val bx=p[t].x-p[j].x;val by=p[t].y-p[j].y
            val cosine=(ax*bx+ay*by)/(hypot(ax,ay)*hypot(bx,by)).coerceAtLeast(.000001f)
            return cosine>.65f && d(m,t)>d(m,j)*1.45f && d(0,t)>d(0,j)+size*.12f
        }
        fun folded(m:Int,j:Int,t:Int)=d(m,t)<size*.85f && d(0,t)<d(0,j)+size*.12f
        val fingers=listOf(Triple(5,6,8),Triple(9,10,12),Triple(13,14,16),Triple(17,18,20))
        val extended=fingers.map { straight(it.first,it.second,it.third) }
        val curled=fingers.map { folded(it.first,it.second,it.third) }
        val thumb=d(4,2)>d(3,2)*1.6f && d(4,5)>size*.8f
        AirShortcutPose.classify(p)?.let { return Pose(it,.85f) }
        val pose=when {
            extended.all { it } -> "Open_Palm"
            extended[0] && extended[1] && curled[2] && curled[3] && d(8,12)>size*.25f -> "Victory"
            curled.all { it } && thumb -> {
                val dx=p[4].x-p[2].x;val dy=p[4].y-p[2].y
                if(abs(dy)>abs(dx)*1.25f && abs(dy)>size*.45f) if(dy<0) "Thumb_Up" else "Thumb_Down" else "None"
            }
            curled.all { it } && !thumb -> "Closed_Fist"
            else -> AirPointing.classify(p,false) ?: if(AirPointing.indexPose(p)) "Pointing_Up" else "None"
        }
        return Pose(pose,if(pose=="None") 0f else .85f)
    }
}

/** Session-local spatial tracks only. No identity, enrollment, or persisted coordinates. */
internal class AirBodyRegions {
    data class Joint(val x:Float,val y:Float,val quality:Float)
    data class Body(val id:Int,val joints:List<Joint>,val at:Long) {
        val center get()=AirPointing.Point((joints[11].x+joints[12].x)/2,(joints[11].y+joints[12].y)/2)
    }
    data class Region(val roi:AirRoi,val body:Int?,val shoulder:AirPointing.Point?)
    var bodies=emptyList<Body>();private set
    private var serial=0
    private var recent=emptyList<Body>()
    fun update(now:Long,poses:List<List<Joint>>) {
        val old=recent.filter { now-it.at<6000 }.toMutableList()
        bodies=poses.filter { it.size>=25 && (it[11].quality>.35f || it[12].quality>.35f) }.map { joints ->
            val c=AirPointing.Point((joints[11].x+joints[12].x)/2,(joints[11].y+joints[12].y)/2)
            val matches=old.filter { hypot(it.center.x-c.x,it.center.y-c.y)<.18f }
            val previous=matches.singleOrNull();if(previous!=null) old.remove(previous)
            Body(previous?.id ?: ++serial,joints,now)
        }
        recent=(bodies+old).distinctBy { it.id }.take(4)
    }
    fun regions(now:Long,aspect:Float):List<Region> = bodies.filter { now-it.at<=2400 }.flatMap { b ->
        listOf(Triple(11,13,15),Triple(12,14,16)).mapNotNull { (s,e,w) ->
            val wrist=b.joints[w];val elbow=b.joints[e];val shoulder=b.joints[s]
            if(wrist.quality<.25f) return@mapNotNull null
            val reliable=wrist.quality>=.55f && elbow.quality>=.4f
            val dx=if(reliable) (wrist.x-elbow.x)*.25f else 0f
            val dy=if(reliable) (wrist.y-elbow.y)*.25f else 0f
            val arm=hypot((wrist.x-elbow.x)*aspect,wrist.y-elbow.y)
            val side=maxOf(.18f,arm*1.8f)*(if(reliable) 1f else 1.7f)
            Region(box(wrist.x+dx,wrist.y+dy,side/aspect,side),b.id,
                b.center.takeIf { shoulder.quality>.5f && b.joints[11].quality>.5f && b.joints[12].quality>.5f })
        }
    }
    companion object {
        fun box(x:Float,y:Float,w:Float,h:Float):AirRoi {
            val width=w.coerceIn(.04f,1f);val height=h.coerceIn(.04f,1f)
            val l=(x-width/2).coerceIn(0f,1-width);val t=(y-height/2).coerceIn(0f,1-height)
            return AirRoi(l,t,l+width,t+height)
        }
        fun refine(points:List<AirPointing.Point>,aspect:Float):AirRoi {
            val l=points.minOf { it.x };val r=points.maxOf { it.x };val t=points.minOf { it.y };val b=points.maxOf { it.y }
            val side=maxOf((r-l)*aspect,b-t)*2.3f
            return box((l+r)/2,(t+b)/2,maxOf(.10f,side/aspect),maxOf(.10f,side))
        }
    }
}
