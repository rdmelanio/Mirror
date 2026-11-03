package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class AirFeedbackStateTest {
    private fun body(rev:Long,activity:Long,tracking:String="OWNED",age:Long=0,trackingAge:Long=0,events:List<Pair<Long,String>> = emptyList(),active:Boolean=true):JSONObject {
        val a=JSONArray();events.forEach { a.put(JSONObject().put("id",it.first).put("event",it.second).put("ageMs",0)) }
        return JSONObject().put("version",2).put("epoch","phone").put("seq",events.firstOrNull()?.first ?: 0).put("events",a)
            .put("snapshot",JSONObject().put("revision",rev).put("activity",activity).put("tracking",tracking).put("ageMs",0)
                .put("interactionAgeMs",age).put("trackingAgeMs",trackingAge).put("active",active))
    }
    @Test fun greenAndAmberTimersAreLocalAndHeartbeatsCannotResurrectOrExtend() {
        val v=AirFeedbackView();v.accept(body(0,0),0);assertNull(v.current(0))
        v.accept(body(1,1),100);assertEquals("GREEN",v.current(100)!!.color)
        v.accept(body(1,1),3000);assertEquals(4100L,v.current(3000)!!.until)
        v.accept(body(2,1,"LOST",age=3000),3100);assertEquals("AMBER",v.current(3100)!!.color)
        v.accept(body(3,1,"LOST",age=6000,trackingAge=3000),6100);assertEquals(13100L,v.current(6100)!!.until)
        assertNull(v.current(13100));v.accept(body(4,1,"LOST",age=15_000,trackingAge=12_000),15100);assertNull(v.current(15100))
        v.accept(body(5,1,"OWNED",age=16_000),16100);assertNull(v.current(16100))
        v.accept(body(6,2),17000);assertEquals("GREEN",v.current(17000)!!.color)
        assertNull(v.current(21000));v.accept(body(7,2,"LOST",age=5000),22000);assertNull(v.current(22000))
    }
    @Test fun expiryAndTransferShowOnceAndServiceLossClearsEverything() {
        val v=AirFeedbackView();v.accept(body(0,0),0)
        val transfer=body(1,1,events=listOf(1L to "TRANSFERRED"))
        v.accept(transfer,100);assertEquals("Control transferred",v.current(100)!!.text)
        v.accept(transfer,1000);assertEquals(1600L,v.current(1000)!!.until)
        assertEquals("GREEN",v.current(1700)!!.color)
        val expired=body(2,1,events=listOf(2L to "EXPIRED"),active=false)
        v.accept(expired,5000);assertTrue(v.current(5000)!!.text.contains("inactive"));assertNull(v.current(7000))
        v.accept(expired,8000);assertNull(v.current(8000))
        v.accept(body(3,2),9000);v.disconnect();assertNull(v.current(9000))
    }
    @Test fun legacyCompatibilityAndNewConsumerNeverReplaysCachedActive() {
        val v=AirFeedbackView()
        v.accept(JSONObject().put("epoch","old").put("seq",1).put("event","ACTIVE"),0);assertNull(v.current(0))
        v.accept(JSONObject().put("epoch","old").put("seq",2).put("event","SENT_RIGHT"),100)
        assertEquals("RIGHT sent",v.current(100)!!.text);assertNull(v.current(1300))
        v.accept(body(5,5,events=listOf(5L to "ACTIVE")),2000);assertNull(v.current(2000))
        v.accept(body(5,5,events=listOf(5L to "ACTIVE")),2100);assertNull(v.current(2100))
    }
    @Test fun serverOrdersEventsNegotiatesSnapshotCursorAndSignsVersionTwoRequests() {
        var now=100L;val e=AirFeedbackEvents { now }
        e.post("SENT_UP");e.post("SENT_RIGHT")
        var b=JSONObject(e.poll(0,0));assertEquals("SENT_UP",b.getString("event"));assertEquals(1L,b.getLong("seq"));assertEquals(2,b.getJSONArray("events").length())
        b=JSONObject(e.poll(1,0));assertEquals("SENT_RIGHT",b.getString("event"))
        e.snapshot(JSONObject().put("active",true).put("tracking","OWNED").put("activity",1))
        assertTrue(JSONObject(e.poll(2,0,stateAfter=-1)).getJSONObject("snapshot").getLong("revision")>0)
        val key=AirFeedbackAuth.key("test pairing");val request=AirFeedbackAuth.requestV2("nonce",2,1)
        assertFalse(AirFeedbackAuth.valid(key,AirFeedbackAuth.requestV2("nonce",2,2),AirFeedbackAuth.sign(key,request)))
        now+=3000;assertEquals(0,JSONObject(e.poll(0,0)).getJSONArray("events").length())
    }
    @Test fun diagnosticsAreBoundedPrivateAndExpireWithoutDiskWrites() {
        var now=0L;val d=AirDiagnostics { now }
        repeat(1000) { now+=300;d.record("Active","OWNED","POINT_UP",.78f,null,"Connected",3.0,250.0,"None") }
        assertTrue(d.text().lines().size<=180);assertFalse(d.text().contains(".5"))
        now+=61000;assertEquals("No recent diagnostics",d.text())
        d.record("192.168.0.1","token:secret","secret",.8f,null,"host",3.0,250.0,"None")
        assertFalse(d.text().contains("192.168"));assertFalse(d.text().contains("secret"))
    }
    @Test fun actualAuthenticatedListenerAcceptsV1AndNegotiatedV2WithoutChangingPairing() {
        val security=PairingAuthority();val code=security.showCode()!!
        val token=security.pair(code,"tv","TV").token!!;val key=AirFeedbackAuth.key(token)
        AirFeedbackServer(security,java.net.InetAddress.getLoopbackAddress(),{true},0).use { server ->
            fun request(version:Int):JSONObject=java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.port).use { socket ->
                socket.soTimeout=2500;val nonce=AirFeedbackAuth.nonce()
                val signed=if(version==2) AirFeedbackAuth.requestV2(nonce,-1,-1) else AirFeedbackAuth.request(nonce,-1)
                val req=JSONObject().put("nonce",nonce).put("after",-1).put("mac",AirFeedbackAuth.sign(key,signed))
                if(version==2) req.put("version",2).put("stateAfter",-1)
                socket.getOutputStream().write((req.toString()+"\n").toByteArray())
                val response=JSONObject(AirFeedbackServer.readLine(socket.getInputStream()))
                assertTrue(AirFeedbackAuth.valid(key,"$nonce:${response.getString("body")}",response.getString("mac")))
                JSONObject(response.getString("body"))
            }
            assertEquals(2,request(1).getInt("version"));assertTrue(request(2).has("snapshot"))
        }
    }

    @Test fun networkTransitCannotRecreateAnAlreadyOldHandOrCommand() {
        val v=AirFeedbackView();v.accept(body(0,0),0)
        v.accept(body(1,1,age=3500),4500,true,1000);assertNull(v.current(4500))
        val delayed=body(2,1,age=5000,events=listOf(1L to "SENT_UP"))
        delayed.getJSONArray("events").getJSONObject(0).put("ageMs",1900)
        v.accept(delayed,6000,true,200);assertNull(v.current(6000))
    }

}
