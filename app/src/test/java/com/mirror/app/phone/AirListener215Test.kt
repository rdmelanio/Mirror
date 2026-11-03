package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AirListener215Test {
    @Test fun authenticatedOldNewAndOverlayMissingListenersHaveActionableDifferentStates() {
        val authority=PairingAuthority()
        AirFeedbackServer(authority,java.net.InetAddress.getLoopbackAddress(),{true},0).use { server ->
            assertTrue(server.launchStatus().contains("not camera-paired"))
            val token=authority.pair(authority.showCode()!!,"tv","TV").token!!;val key=AirFeedbackAuth.key(token)
            assertTrue(server.launchStatus().contains("No TV companion contact"))
            fun poll(v:Int,overlay:Boolean=true):JSONObject=java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.port).use { s ->
                s.soTimeout=2000
                val nonce=AirFeedbackAuth.nonce();val ack=JSONObject().put("listener",JSONObject().put("overlay",overlay)).toString()
                val signed=if(v==3) AirFeedbackAuth.requestV3(nonce,-1,-1,ack) else AirFeedbackAuth.requestV2(nonce,-1,-1)
                val request=JSONObject().put("version",v).put("nonce",nonce).put("after",-1).put("stateAfter",-1).put("mac",AirFeedbackAuth.sign(key,signed))
                if(v==3) request.put("launchAck",ack).put("capability","apps-v1")
                s.getOutputStream().write((request.toString()+"\n").toByteArray())
                val response=JSONObject(AirFeedbackServer.readLine(s.getInputStream()));assertTrue(AirFeedbackAuth.valid(key,"$nonce:${response.getString("body")}",response.getString("mac")));JSONObject(response.getString("body"))
            }
            poll(2);assertFalse(server.launchSupported());assertTrue(server.launchStatus().contains("update TV APK"))
            poll(3,false);assertTrue(server.launchSupported());assertTrue(server.launchStatus().contains("overlay permission missing"))
            poll(3,true);assertTrue(server.launchStatus().contains("capability negotiated"))
            assertTrue(server.launch(AirApp.STREMIO));server.cancelLaunches();assertFalse(poll(3).has("launch"))
            assertTrue(server.launchStatus().contains("discarded"))
        }
    }
    @Test fun nativePacketFailureAfterConstructionClosesBothRoutesAndReportsOnlyOnce() {
        class Image(val bitmap:Boolean):AutoCloseable { var closed=false;override fun close() { check(!closed);closed=true } }
        val images=mutableListOf<Image>();var notice=0
        val policy=AirInputPolicy { notice++ }
        repeat(50) {
            val value=policy.process("direct",{ bitmap -> Image(bitmap).also(images::add) }, { image -> if(!image.bitmap) throw IllegalArgumentException("The size of the buffer should be: 691200 but is 1228800") else 9 })
            assertEquals(9,value)
        }
        assertEquals(1,notice);assertEquals(51,images.size);assertTrue(images.all { it.closed })
    }
    @Test fun recoverySchedulerIsBoundedAndThermalLimitsOverrideIt() {
        val w=AirWorkload();w.observe(0,false,true,true);assertEquals(AirStage.RECOVERY,w.stage);assertEquals(100L,w.interval())
        w.thermal(100,3);assertTrue(w.interval(250.0)>=500)
        w.observe(4000,false,false,false);assertEquals(AirStage.ECO,w.stage);assertTrue(w.interval()>=1000)
    }
}
