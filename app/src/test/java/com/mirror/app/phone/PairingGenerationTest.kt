package com.mirror.app.phone
import com.mirror.app.core.PairingGeneration
import org.junit.Assert.*
import org.junit.Test
class PairingGenerationTest {
    @Test fun cancellationAndNewAttemptPreventLatePersistence() {
        val g=PairingGeneration();var saved="working"
        val first=g.begin();g.begin()
        assertFalse(g.commit(first) { saved="cancelled";true });assertEquals("working",saved)
        val latest=g.begin()
        assertTrue(g.commit(latest) { saved="new";true })
        assertFalse(g.commit(first) { saved="old";true });assertEquals("new",saved)
        g.begin();assertFalse(g.commit(latest) { saved="late";true });assertEquals("new",saved)
    }
    @Test fun failedPersistenceIsNotSuccessfulPairing() {
        val g=PairingGeneration();val a=g.begin();assertFalse(g.commit(a) { false })
    }
}
