package com.mirror.app.phone.roster

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One process-wide RosterStore monitor owns this journal. No live file changes before READY.
 * Interrupted commits roll back on the next load. Backups remain until recovery succeeds. */
internal class RosterTransaction(private val dir: File, private val boundary: (String) -> Unit = {}) {
    private val journal = File(dir, ".import-transaction")
    private val names = listOf("latest.pdf", "previous.pdf", "roster.json")
    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun replace(target: File, bytes: ByteArray) {
        val temp = File(dir, target.name + ".import-new")
        write(temp, bytes)
        if (!temp.renameTo(target)) throw IOException("Roster atomic replacement failed")
    }
    fun recover() {
        if (!journal.exists()) return
        if (File(journal, "READY").exists() && !File(journal, "COMMITTED").exists()) {
            names.forEach { name ->
                val backup = File(journal, "$name.old")
                val target = File(dir, name)
                if (backup.exists()) replace(target, backup.readBytes())
                else if (target.exists() && !target.delete()) throw IOException("Roster rollback failed")
            }
        }
        check(journal.deleteRecursively()) { "Roster journal cleanup failed" }
    }
    fun commit(pdf: ByteArray?, json: String) {
        recover()
        check(journal.mkdir()) { "Cannot stage roster" }
        try {
            names.forEach { name ->
                val source = File(dir, name)
                if (source.exists()) write(File(journal, "$name.old"), source.readBytes())
                boundary("backup:$name")
            }
            if(pdf!=null) { write(File(journal, "latest.new"), pdf); boundary("stage:pdf") }
            write(File(journal, "roster.new"), json.toByteArray(Charsets.UTF_8)); boundary("stage:json")
            write(File(journal, "READY"), byteArrayOf(1)); boundary("ready")
            val previous = File(journal, "latest.pdf.old")
            if (pdf!=null && previous.exists()) replace(File(dir, "previous.pdf"), previous.readBytes())
            boundary("replace:previous")
            if(pdf!=null) replace(File(dir, "latest.pdf"), File(journal, "latest.new").readBytes()); boundary("replace:latest")
            replace(File(dir, "roster.json"), File(journal, "roster.new").readBytes()); boundary("replace:json")
            boundary("before:commit")
            val marker=File(journal, "COMMITTED.tmp")
            write(marker, byteArrayOf(1))
            if(!marker.renameTo(File(journal,"COMMITTED"))) throw IOException("Roster commit marker failed")
        } catch (failure: Exception) {
            try { recover() } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
        // Cleanup failure cannot turn a committed import into a reported failure.
        runCatching { recover() }
    }
}
