package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RosterTransaction218Test {
    private val boundaries=listOf("backup:latest.pdf","backup:previous.pdf","backup:roster.json","stage:pdf","stage:json","ready","replace:previous","replace:latest","replace:json","before:commit")
    @Test fun ioFailureAtEveryBoundaryPreservesLastGoodAndRepeatedFailures() {
        boundaries.forEach { point ->
            val dir=Files.createTempDirectory("roster").toFile()
            try {
                val originals=mapOf("latest.pdf" to "latest", "previous.pdf" to "previous", "roster.json" to "json")
                originals.forEach { (name,value)->File(dir,name).writeText(value) }
                repeat(2) {
                    try { RosterTransaction(dir) { if(it==point) throw IOException("injected") }.commit("bad".toByteArray(),"new");fail(point) }
                    catch (_:IOException) {}
                    originals.forEach { (name,value)->assertEquals(point,value,File(dir,name).readText()) }
                }
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun processInterruptionRollsBackOnNextLoadAtEveryBoundary() {
        boundaries.forEach { point ->
            val dir=Files.createTempDirectory("roster").toFile()
            try {
                File(dir,"latest.pdf").writeText("old");File(dir,"roster.json").writeText("old json")
                try { RosterTransaction(dir) { if(it==point) throw AssertionError("simulated process death") }.commit("new".toByteArray(),"new json") }
                catch (_:AssertionError) {}
                RosterTransaction(dir).recover()
                assertEquals(point,"old",File(dir,"latest.pdf").readText())
                assertEquals(point,"old json",File(dir,"roster.json").readText());assertFalse(File(dir,"previous.pdf").exists())
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun successRotatesPdfAndJsonTogether() {
        val dir=Files.createTempDirectory("roster").toFile()
        try {
            val tx=RosterTransaction(dir)
            tx.commit("first".toByteArray(),"first json");tx.commit("second".toByteArray(),"second json");tx.recover()
            assertEquals("first",File(dir,"previous.pdf").readText());assertEquals("second",File(dir,"latest.pdf").readText())
            assertEquals("second json",File(dir,"roster.json").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedPdfCanRetryAndJavaHashCollisionsAreNotDuplicates() {
        val d=AcceptedPdf();val a=byteArrayOf(0,31);val b=byteArrayOf(1,0)
        assertEquals(a.contentHashCode(),b.contentHashCode())
        assertFalse(d.accept(a) { false });assertFalse(d.duplicate(a))
        assertTrue(d.accept(a) { true });assertTrue(d.duplicate(a));assertFalse(d.duplicate(b))
        assertTrue(d.accept(a) { error("duplicate saved twice") })
        assertTrue(d.accept(b) { true });assertTrue(d.duplicate(b));assertFalse(d.duplicate(a))
    }
}
