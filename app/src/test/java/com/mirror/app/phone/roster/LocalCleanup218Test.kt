package com.mirror.app.phone.roster
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
class LocalCleanup218Test {
    @Test fun failuresRemainFailuresAndExplicitRetryCanSucceed() {
        val results=mutableListOf<Boolean>();var browserAttempts=0
        LocalLogoutCleanup.run({throw IOException()}, { done->browserAttempts++;done(true) },results::add)
        LocalLogoutCleanup.run({true}, { throw IOException() },results::add)
        LocalLogoutCleanup.run({true}, { done->done(false);done(true) },results::add)
        LocalLogoutCleanup.run({true}, { done->done(true) },results::add)
        assertEquals(1,browserAttempts);assertEquals(listOf(false,false,false,true),results)
    }
    @Test fun explicitClearReportsAnyFailedBoundaryAndStillAttemptsRemainingStores() {
        var attempted=0
        assertFalse(LocalLogoutCleanup.all({attempted++;false},{attempted++;throw IOException()},{attempted++;true}))
        assertEquals(3,attempted)
        assertTrue(LocalLogoutCleanup.all({true},{true}))
    }
    @Test fun rawCleanupPreservesOfflineRosterAndSession() {
        val dir=Files.createTempDirectory("capture").toFile()
        try {
            val preserved=listOf("latest.pdf","previous.pdf","roster.json","capture.log","ecrew-login.enc")
            preserved.forEach { File(dir,it).writeText("keep") }
            File(dir,"schedule-data").mkdir();File(dir,"schedule-data/raw.json").writeText("private")
            File(dir,"captured-schedule-data.zip").writeText("private zip")
            assertTrue(RawCaptureCleanup.clear(dir));assertTrue(RawCaptureCleanup.clear(dir))
            preserved.forEach { assertEquals("keep",File(dir,it).readText()) }
            assertFalse(File(dir,"schedule-data").exists());assertFalse(File(dir,"captured-schedule-data.zip").exists())
        } finally { dir.deleteRecursively() }
    }
}
