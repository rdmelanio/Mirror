package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Audit218SecurityTest {
    @Test fun simultaneousPairingConsumesOneCodeAndRequiresRegeneration() {
        val authority=PairingAuthority()
        val code=authority.showCode()!!
        val pool=Executors.newFixedThreadPool(8)
        val gate=CountDownLatch(1)
        try {
            val attempts=(1..8).map { n -> pool.submit<Int> { gate.await();authority.pair(code,"$n","TV $n").status } }
            gate.countDown()
            val results=attempts.map { it.get(5,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it==200 });assertEquals(7,results.count { it==403 })
            assertEquals(0L,authority.remainingSeconds());assertEquals(1,authority.list().size)
            assertEquals(200,authority.pair(authority.showCode()!!,"second","Second TV").status)
        } finally { pool.shutdownNow() }
    }
    @Test fun invalidIdentityDoesNotConsumeCode() {
        val a=PairingAuthority();val code=a.showCode()!!
        assertEquals(400,a.pair(code,"","TV").status)
        assertEquals(200,a.pair(code,"id","TV").status)
        assertEquals(403,a.pair(code,"id","TV").status)
    }
    private fun basic(raw:String)="Basic "+Base64.getEncoder().encodeToString(raw.toByteArray())
    @Test fun browserRequiresUsernameAndCooldownExpiresWithoutRegeneration() {
        var time=0L;val a=PairingAuthority(elapsed={time});a.setBrowserPassword("secret")
        assertFalse(a.browserAuth(basic("other:secret")))
        assertTrue(a.browserAuth(basic("mirror:secret")))
        repeat(5) { assertFalse(a.browserAuth("Basic invalid")) }
        assertFalse(a.browserAuth(basic("mirror:secret")))
        time=1000;assertTrue(a.browserAuth(basic("mirror:secret")))
        assertFalse(a.browserAuth("Basic "+"a".repeat(4096)))
    }
    @Test fun legacyPasswordUpgradesOnlyAfterAuthentication() {
        var saved="";val seed=PairingAuthority()
        val old=org.json.JSONObject().put("browserSalt","old-salt").put("browserHash",seed.tokenHash("old-saltsecret")).toString()
        val a=PairingAuthority(old,persist={saved=it})
        assertFalse(a.browserAuth(basic("wrong:secret")))
        assertFalse(saved.contains("browserKdf"))
        assertTrue(a.browserAuth(basic("mirror:secret")))
        assertTrue(saved.contains("pbkdf2-sha256-210000"));assertFalse(saved.contains("secret"))
        assertTrue(PairingAuthority(saved).browserAuth(basic("mirror:secret")))
    }
    @Test fun observationAndQueueDeadlinesAreIndependentAndRejectClockReversal() {
        val reasons=mutableListOf<String>();val q=AirKeyQueue(event={s,_,_->reasons.add(s)})
        assertFalse(q.offer(AirGestures.Command.UP,1001,0))
        assertTrue(q.offer(AirGestures.Command.UP,1000,0));assertNotNull(q.take(1000))
        assertTrue(q.offer(AirGestures.Command.UP,1000,100));assertNull(q.take(1101))
        assertTrue(q.offer(AirGestures.Command.UP,2000));assertNotNull(q.take(2400))
        assertTrue(q.offer(AirGestures.Command.UP,3000));assertNull(q.take(3401))
        assertFalse(q.offer(AirGestures.Command.UP,4000,4001))
        assertTrue(q.offer(AirGestures.Command.UP,5000));assertNull(q.take(4999))
        assertTrue(reasons.containsAll(listOf("OBSERVATION_EXPIRED","EXPIRED","CLOCK_GAP")))
    }
}
