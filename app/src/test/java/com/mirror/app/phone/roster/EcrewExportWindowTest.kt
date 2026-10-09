package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class EcrewExportWindowTest {
    @Test fun armExtendAndExpireIncludingLateManualPdfTap() {
        var now = 0L
        val events = mutableListOf<String>()
        val window = EcrewExportWindow({ now }, events::add)
        assertFalse(window.armed)
        window.arm("robot CLICK_PRINT"); assertTrue(window.armed)
        now = 60_000; window.arm("trusted PDF tap")
        now = 180_000; assertTrue(window.armed)
        now = 239_999; assertTrue(window.armed)
        now = 240_000; assertFalse(window.armed); assertFalse(window.armed)
        assertEquals(3, events.size)
        assertTrue(events[0].startsWith("armed")); assertTrue(events[1].startsWith("extended")); assertEquals("expired", events[2])
        window.arm("trusted export tap"); assertTrue(window.armed)
        window.clear(); assertFalse(window.armed)
    }
    @Test fun earlyPdfCannotSkipExportStepsAndCaptureWaitsSixtySeconds() {
        var now = 0L
        val machine = EcrewAutomation({ now }, { _, _ -> }, {}, { _, _ -> })
        machine.start()
        for (reply in listOf("schedule", "clear", "printed", "preview", "export")) {
            assertFalse(machine.pdf())
            machine.response(machine.request, reply)
        }
        assertEquals(EcrewAutomation.Step.CHOOSE_PDF, machine.step)
        assertFalse(machine.pdf()); machine.response(machine.request, "pdf")
        now = 59_999; machine.tick(); assertTrue(machine.active)
        now = 60_000; machine.tick(); assertFalse(machine.active)
    }
    @Test fun capturedScheduleMakesFailedPdfFetchCountAsDataCaptured() {
        var result = false
        val machine = EcrewAutomation({ 0L }, { _, _ -> }, {}, { success, _ -> result = success })
        machine.start(); machine.scheduleDataSaved(); machine.stop(reason = "PDF timeout")
        assertTrue(result)
    }
}
