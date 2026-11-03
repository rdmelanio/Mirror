package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.UnknownFormatConversionException

class AirRecognitionHealthTest {
    @Test fun originalInterpolationReproducesFailureAndProductionFormatterTreatsPercentAsData() {
        for(text in listOf("Open_Palm 70%","Closed_Fist 85%","Victory 60%","Open_Palm 70%, Victory 60%","None")) {
            if(text.contains('%')) {
                try { (text+" · Claiming %.1f/2.0 seconds").format(java.util.Locale.US,1.4);fail("must reproduce") }
                catch(_:UnknownFormatConversionException) {}
            }
            assertEquals("$text · Claiming 1.4/2.0 seconds",AirRecognitionStatus.claiming(text,1400))
        }
    }
    @Test fun manyFramesUseActualFormatterWithoutDisablingRecognition() {
        val h=AirRecognitionHealth {};var processed=0
        repeat(5000) { i ->
            assertTrue(h.run(i*333L) { AirRecognitionStatus.claiming("Open_Palm ${50+i%46}%, Victory 60%",(i%2001).toLong());processed++ })
        }
        assertEquals(5000,processed);assertFalse(h.unavailable);assertEquals("",h.lastError)
    }
    @Test fun badFrameResetsOwnershipAndNextValidFrameProcessesAutomatically() {
        val g=AirGestures();g.claim(0,.5f,.5f)
        var reset=0;val h=AirRecognitionHealth { reset++;g.reset() };var processed=0
        assertFalse(h.run(100) { throw IllegalArgumentException("Malformed JPEG") })
        assertFalse(g.armed);assertEquals(1,reset);assertFalse(h.unavailable)
        assertFalse(h.run(599) { processed++ })
        assertTrue(h.run(600) { processed++;AirRecognitionStatus.claiming("Open_Palm 70%",0) })
        assertEquals(1,processed);assertTrue(h.lastError.startsWith("Recovered"));assertTrue(h.ready(601))
    }
    @Test fun diagnosticExceptionIsRecoverableAndRepeatedFailuresBackOff() {
        val h=AirRecognitionHealth {};var now=0L
        repeat(12) {
            assertFalse(h.run(now) { throw UnknownFormatConversionException(" ") })
            assertFalse(h.unavailable);val delay=h.retryAt-now
            assertTrue(delay in 500L..30_000L);assertFalse(h.ready(h.retryAt-1));now=h.retryAt
        }
        assertTrue(h.lastError.contains("failure 12"))
        assertTrue(h.run(now) {});assertEquals(0,h.failures)
    }
    @Test fun retryStartsAfterSlowFailedProcessingAndCleanupRuns() {
        var clock=0L;var clean=false
        val h=AirRecognitionHealth({clock}) {}
        h.run(0) { try { clock=2000;throw IllegalStateException("frame failed") } finally { clean=true } }
        assertTrue(clean);assertEquals(2500L,h.retryAt)
    }
    @Test fun invalidModelOrMissingNativeLibraryRequiresExplicitReset() {
        for(e in listOf(AirModelUnavailable(IllegalArgumentException("invalid model")),UnsatisfiedLinkError("missing library"))) {
            val h=AirRecognitionHealth {};h.run(0) { throw e }
            assertTrue(h.unavailable);assertFalse(h.run(60_000) { fail("terminal") })
            h.reset();assertTrue(h.run(60_000) {})
        }
    }
}
