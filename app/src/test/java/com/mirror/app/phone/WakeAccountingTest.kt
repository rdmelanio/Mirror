package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class WakeAccountingTest {
    @Test fun acquisitionAndReleaseTotalsDoNotCountIdleTime() {
        val w=WakeAccounting()
        assertTrue(w.update(true,100).contains("acquisitions 1"))
        assertTrue(w.update(true,200).contains("100ms"))
        assertTrue(w.update(false,300).contains("200ms"))
        assertTrue(w.update(false,1000).contains("200ms"))
        assertTrue(w.update(true,1100).contains("acquisitions 2"))
        assertTrue(w.update(false,1200).contains("300ms"))
    }
}
