package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class EcrewAutomationTest {
    private class FakePort {
        var now = 0L
        val commands = mutableListOf<Pair<String, Int>>()
        val notices = mutableListOf<Boolean>()
        val results = mutableListOf<Pair<Boolean, String>>()
        val states = mutableListOf<String>()
        val machine = EcrewAutomation({ now }, { command, id -> commands += command to id },
            { notices += it }, { success, reason -> results += success to reason }, { states += it })
        fun reply(result: String) { machine.tick(); machine.response(commands.last().second, result) }
        fun toCapture(pending: Boolean = false) {
            assertTrue(machine.start()); reply("schedule"); reply(if (pending) "pending" else "clear"); reply("printed")
        }
    }
    @Test fun pendingChangesAreReportedButNeverConfirmed() {
        val p = FakePort(); p.toCapture(true)
        assertEquals(listOf("openSchedule", "checkPending", "print"), p.commands.map { it.first })
        assertEquals(listOf(true), p.notices)
        assertTrue(p.machine.pdf()); p.machine.parsed(true, false); p.reply("exit")
        assertEquals(listOf("OPEN_MY_SCHEDULE", "CHECK_PENDING_CHANGES", "CLICK_PRINT", "CAPTURE_PDF", "PARSE", "EXIT"), p.states)
        assertEquals(listOf(true to "success"), p.results)
        assertFalse(p.commands.any { it.first.contains("confirm", true) })
    }
    @Test fun nextPeriodRunsOnceAfterExitAndUsesSameMachine() {
        val p = FakePort(); p.toCapture(); assertTrue(p.machine.pdf()); p.machine.parsed(true, true)
        p.reply("exit"); p.reply("next"); p.reply("printed")
        assertTrue(p.machine.pdf()); p.machine.parsed(true, true); p.reply("exit")
        assertEquals(listOf("openSchedule", "checkPending", "print", "exit", "nextPeriod", "print", "exit"), p.commands.map { it.first })
        assertEquals(1, p.results.size); assertTrue(p.results.single().first)
    }
    @Test fun manualPrintDoesNotOpenScheduleOrFetchAnotherPeriod() {
        val p = FakePort(); p.machine.manualPrint(); p.machine.tick()
        assertEquals("capture", p.commands.single().first)
        assertTrue(p.machine.pdf()); p.machine.parsed(true, true); p.reply("exit")
        assertFalse(p.commands.any { it.first == "nextPeriod" })
    }
    @Test fun trustedManualPrintCanTakeOverAnAutomaticScheduleStep() {
        val p = FakePort(); p.machine.start(); p.machine.tick()
        val opening = p.commands.single().second
        p.machine.manualPrint(); p.machine.response(opening, "schedule")
        assertEquals(EcrewAutomation.Step.CAPTURE_PDF, p.machine.step)
        assertTrue(p.machine.pdf()); p.machine.parsed(true, false); p.reply("exit")
        assertTrue(p.results.single().first)
    }
    @Test fun stepAndTotalTimeoutsAreBoundedAndDoNotRetrySession() {
        val step = FakePort(); step.machine.start(); step.now = 25_000; step.machine.tick()
        assertFalse(step.machine.active); assertEquals(1, step.results.size)
        step.machine.tick(); assertEquals(1, step.results.size)
        val total = FakePort(); total.machine.start()
        total.now = 24_000; total.reply("schedule")
        total.now = 48_000; total.reply("clear")
        total.now = 72_000; total.reply("printed")
        total.now = 90_000; total.machine.tick()
        assertFalse(total.machine.active); assertFalse(total.results.single().first)
    }
    @Test fun staleMessagesAndLatePdfCannotAdvanceNewRun() {
        val p = FakePort(); p.machine.start(); p.machine.tick(); val old = p.commands.single().second
        p.machine.stop(); p.machine.start(); p.machine.response(old, "schedule")
        assertEquals(EcrewAutomation.Step.OPEN_MY_SCHEDULE, p.machine.step)
        assertFalse(p.machine.pdf()); assertFalse(p.machine.start())
        p.reply("terminated"); assertFalse(p.machine.active); assertFalse(p.machine.pdf())
    }
    @Test fun readsMagicAndEnforcesLimitEvenWithoutContentLength() {
        val bytes = "%PDF-fake".toByteArray(); var closed = false
        val stream = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        assertArrayEquals(bytes, EcrewPdfBytes.read(stream)); assertTrue(closed)
        assertNull(EcrewPdfBytes.read(ByteArrayInputStream("<html>Login</html>".toByteArray())))
        assertNull(EcrewPdfBytes.read(ByteArrayInputStream("%PD".toByteArray())))
        assertNull(EcrewPdfBytes.read(null))
        assertNull(EcrewPdfBytes.read(object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < 4) "%PDF"[at++].code else { at++; 65 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = read().toByte()
                return len
            }
        }))
    }
    @Test fun nativeMessagesMustComeFromEcrewOutsideLoginAndConfirmationIsForbidden() {
        assertTrue(EcrewPortPolicy.page("https://ecrew.cebupacificair.com/eCrew/Dashboard/"))
        for (url in listOf("https://ecrew.cebupacificair.com/eCrew/Login/", "https://ecrew.cebupacificair.com/eCrew/LOGIN", "https://other.test/eCrew/Dashboard/", "http://ecrew.cebupacificair.com/eCrew/Dashboard/", "https://ecrew.cebupacificair.com:444/eCrew/Dashboard/", "https://user@ecrew.cebupacificair.com/eCrew/Dashboard/")) assertFalse(url, EcrewPortPolicy.page(url))
        assertFalse(EcrewPortPolicy.clickAllowed(" CONFIRM ALL CHANGES (2) "))
        assertTrue(EcrewPortPolicy.clickAllowed("Print"))
    }
}
