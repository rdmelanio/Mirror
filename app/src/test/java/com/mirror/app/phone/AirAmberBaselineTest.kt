package com.mirror.app.phone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AirAmberBaselineTest {
    private fun state(rev:Long,activity:Long,tracking:String)=JSONObject().put("epoch","p").put("seq",0).put("snapshot",JSONObject().put("revision",rev).put("activity",activity).put("tracking",tracking).put("active",true).put("ageMs",0).put("interactionAgeMs",0).put("trackingAgeMs",0))
    @Test fun oneDroppedObservationNeverFlashesAmber() {
        val v=AirFeedbackView();v.accept(state(0,0,"OWNED"),0);v.accept(state(1,1,"OWNED"),100)
        v.accept(state(2,1,"LOST"),400);assertEquals("GREEN",v.current(400)!!.color)
        v.accept(state(3,1,"OWNED"),710);assertEquals("GREEN",v.current(800)!!.color)
    }
}
