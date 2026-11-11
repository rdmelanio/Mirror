package com.mirror.app.phone.roster
import java.io.File
internal object RawCaptureCleanup {
    /** Deliberately excludes offline roster, settings, logs, login and Gecko session. */
    fun clear(rosterDirectory:File):Boolean {
        var success=true
        for(name in listOf("schedule-data","captured-schedule-data.zip")) {
            val file=File(rosterDirectory,name)
            val cleared=runCatching { !file.exists() || file.deleteRecursively() }.getOrDefault(false)
            success=success && cleared
        }
        return success
    }
}
