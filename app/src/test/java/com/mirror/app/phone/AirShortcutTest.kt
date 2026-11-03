package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AirShortcutTest {
    private fun pose(thumb:Boolean,index:Boolean,middle:Boolean,ring:Boolean,pinky:Boolean):List<AirPointing.Point> {
        val p=MutableList(21) { AirPointing.Point(.5f,.65f) };p[0]=AirPointing.Point(.5f,.8f)
        for((i,extended) in listOf(5 to index,9 to middle,13 to ring,17 to pinky)) {
            val x=.42f+(i-5)*.018f;p[i]=AirPointing.Point(x,.6f);p[i+1]=AirPointing.Point(x,.5f)
            p[i+2]=AirPointing.Point(x,if(extended) .4f else .59f);p[i+3]=AirPointing.Point(x,if(extended) .3f else .65f)
        }
        p[2]=AirPointing.Point(.38f,.68f);p[3]=AirPointing.Point(if(thumb) .32f else .44f,.65f);p[4]=AirPointing.Point(if(thumb) .21f else .49f,.62f)
        return p
    }
    @Test fun geometryDistinguishesShakaILoveYouThreeFingersVictoryAndThumbs() {
        assertEquals("SHAKA",AirShortcutPose.classify(pose(true,false,false,false,true)))
        assertNull(AirShortcutPose.classify(pose(true,true,false,false,true)))
        assertEquals("THREE_FINGERS",AirShortcutPose.classify(pose(false,true,true,true,false)))
        assertNull(AirShortcutPose.classify(pose(false,true,true,false,false)))
        for(thumb in listOf(true,false)) assertNull(AirShortcutPose.classify(pose(thumb,false,false,false,false)))
    }
    @Test fun deliberateShortcutsBridgeDropoutAndLatchWhileShortFistRemainsNeutral() {
        for((name,target,duration) in listOf(Triple("SHAKA",AirApp.YOUTUBE,1000L),Triple("THREE_FINGERS",AirApp.NETFLIX,1100L),Triple("Closed_Fist",AirApp.STREMIO,1450L))) {
            val s=AirShortcuts();val accepted=mutableListOf<AirApp>()
            var now=0L
            while(now<duration+2500) {
                s.update(now,if(now==620L) "None" else name,if(name=="Closed_Fist") .8f else .01f,.65f,AirApp.entries.toSet())?.let { accepted.add(it) };now+=310
            }
            assertEquals(listOf(target),accepted)
            s.update(now,"Open_Palm",.8f,.65f,AirApp.entries.toSet());s.update(now+310,"Open_Palm",.8f,.65f,AirApp.entries.toSet())
            now+=620;repeat(7) { s.update(now+it*310,name,.8f,.65f,AirApp.entries.toSet())?.let { accepted.add(it) } }
            assertEquals(listOf(target,target),accepted)
        }
        val short=AirShortcuts();assertNull(short.update(0,"Closed_Fist",.9f,.65f,AirApp.entries.toSet()));assertNull(short.update(600,"Closed_Fist",.9f,.65f,AirApp.entries.toSet()))
    }
    @Test fun productionDispatcherBlocksCalibrationAmbiguityRecoveryAndOldTv() {
        for(calibration in listOf(false,true)) for(supported in listOf(false,true)) {
            val launched=mutableListOf<AirApp>();var now=0L
            val s=AirControlSession(household=true,launch={launched.add(it)})
            fun frame(name:String,two:Boolean=false) {
                now+=310;val hands=mutableListOf(AirOwnership.Hand(.3f,.5f,.08f,name,.8f));if(two) hands.add(hands[0].copy(x=.32f))
                s.update(now,hands,true,calibration,310,.65f,50,false,400,60_000,AirNavigation.JOYSTICK,50,AirApp.entries.toSet(),supported)
            }
            repeat(5) { frame("Open_Palm") };repeat(8) { frame("SHAKA",true) };assertTrue(launched.isEmpty())
            repeat(12) { frame("SHAKA") }
            assertEquals(if(!calibration && supported) listOf(AirApp.YOUTUBE) else emptyList<AirApp>(),launched)
        }
    }
    @Test fun launchRequestsAreKeyBoundExpiringAllowlistedAndReplayProof() {
        var now=0L;val mailbox=AirLaunchMailbox { now };assertFalse(mailbox.submit(AirApp.YOUTUBE))
        mailbox.authorize("paired TV");assertTrue(mailbox.submit(AirApp.YOUTUBE));assertFalse(mailbox.submit(AirApp.NETFLIX));assertNull(mailbox.payload("other paired TV"))
        val body=mailbox.payload("paired TV")!!;val receiver=AirLaunchReceiver();val command=receiver.reserve(body,50)!!
        assertEquals(AirApp.YOUTUBE,command.second);assertNull(receiver.reserve(body,50));assertNull(AirLaunchReceiver(receiver.saved()).reserve(body,0))
        mailbox.ack("wrong",JSONObject().put("id",command.first).put("result","ATTEMPTED"));assertNotNull(mailbox.payload("paired TV"))
        mailbox.ack("paired TV",JSONObject().put("id",command.first).put("result","MISSING"));assertTrue(mailbox.status.contains("not installed"))
        for(result in listOf("BLOCKED","ATTEMPTED","FAILED")) {
            mailbox.submit(AirApp.STREMIO);val r=mailbox.payload("paired TV")!!
            mailbox.ack("paired TV",JSONObject().put("id",r.getString("id")).put("result",result))
            assertFalse(mailbox.status.contains("confirmed successfully"))
        }
        mailbox.submit(AirApp.NETFLIX);val old=mailbox.payload("paired TV")!!;now=4000
        assertNull(mailbox.payload("paired TV"));assertNull(receiver.reserve(old,4000))
        assertNull(receiver.reserve(JSONObject().put("id",AirFeedbackAuth.nonce()).put("app","arbitrary.package").put("remainingMs",3000),0))
    }
    @Test fun authenticatedVersionThreeBindsCapabilitiesAndAcknowledgements() {
        val security=PairingAuthority();val token=security.pair(security.showCode()!!,"tv","TV").token!!;val key=AirFeedbackAuth.key(token)
        AirFeedbackServer(security,java.net.InetAddress.getLoopbackAddress(),{true},0).use { server ->
            fun poll(ack:String=""):JSONObject=java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.port).use { socket ->
                socket.soTimeout=2000;val nonce=AirFeedbackAuth.nonce();val body=AirFeedbackAuth.requestV3(nonce,-1,-1,ack)
                val request=JSONObject().put("version",3).put("capability","apps-v1").put("nonce",nonce).put("after",-1).put("stateAfter",-1).put("launchAck",ack).put("mac",AirFeedbackAuth.sign(key,body))
                socket.getOutputStream().write((request.toString()+"\n").toByteArray())
                val response=JSONObject(AirFeedbackServer.readLine(socket.getInputStream()));val value=response.getString("body")
                assertTrue(AirFeedbackAuth.valid(key,"$nonce:$value",response.getString("mac")));JSONObject(value)
            }
            assertFalse(server.launchSupported());assertEquals(3,poll().getInt("version"));assertTrue(server.launchSupported())
            assertTrue(server.launch(AirApp.NETFLIX));val request=poll().getJSONObject("launch")
            poll(JSONObject().put("id",request.getString("id")).put("result","BLOCKED").toString())
            assertTrue(server.launchStatus().contains("blocked"));assertFalse(poll().has("launch"))
            val signed=AirFeedbackAuth.requestV3("nonce",0,0,"");assertFalse(AirFeedbackAuth.valid(key,AirFeedbackAuth.requestV3("nonce",0,0,"tampered"),AirFeedbackAuth.sign(key,signed)))
        }
    }
}
