package com.mirror.app.phone

/** Memory only, time/entry bounded, no coordinates or identifiers; callers supply enumerated values only. */
internal class AirDiagnostics(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private val rows=java.util.ArrayDeque<Pair<Long,String>>()
    private var prior="";private var at=-1000L
    @Synchronized fun record(state:String,tracking:String,pose:String,score:Float,accepted:AirGestures.Command?,transport:String,fps:Double,ms:Double,thermal:String,detail:String="") {
        val labels=setOf("None","Open_Palm","Closed_Fist","Thumb_Up","Thumb_Down","Victory","Pointing_Up","POINT_UP","POINT_DOWN","POINT_LEFT","POINT_RIGHT")
        val safePose=if(pose in labels) pose else "unknown"
        fun safe(v:String)=v.takeIf { it.matches(Regex("[A-Za-z_ ]{1,48}")) } ?: "unknown"
        val text="${safe(state)} | ${safe(tracking)} | $safePose ${(score.coerceIn(0f,1f)*100).toInt()}% | accepted=${accepted?.name ?: "none"} | ${safe(transport)} | fps=${fps.toInt()} latency=${ms.toInt()}ms thermal=${safe(thermal)} | ${safe(detail)}"
        val now=clock();trim(now)
        if(text==prior && now-at<2000 || now-at<250) return
        rows.add(now to text);prior=text;at=now;while(rows.size>180) rows.removeFirst()
    }
    private fun trim(now:Long) { while(rows.isNotEmpty() && now-rows.first.first>60_000) rows.removeFirst() }
    @Synchronized fun text():String { val now=clock();trim(now);return rows.joinToString("\n") { "${(now-it.first)/1000}s ago | ${it.second}" }.ifEmpty { "No recent diagnostics" } }
    companion object {
        fun reason(status:String):String=when {
            status.contains("timeout") -> "session expired"
            status.contains("ambiguous") -> "ownership ambiguous"
            status.contains("transport") -> "transport blocked"
            status.contains("unavailable") -> "tracking paused"
            status.contains("recovered") -> "tracking recovered"
            status.contains("dropout") || status.contains("uncertain") -> "classification uncertain"
            status.contains("released") -> "gesture released"
            status.contains("held") -> "waiting release"
            status.contains("stability") -> "waiting stability"
            status.contains("accepted") -> "command accepted"
            status.contains("transferred") -> "control transferred"
            status.contains("Claiming") -> "claim pending"
            status.contains("Disarmed") -> "explicit reset"
            else -> "tracking active"
        }
    }

}
