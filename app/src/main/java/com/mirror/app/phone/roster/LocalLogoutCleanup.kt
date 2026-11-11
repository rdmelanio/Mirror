package com.mirror.app.phone.roster

/** Always attempt both local stores; exceptions and duplicate callbacks cannot become success. */
internal object LocalLogoutCleanup {
    fun all(vararg steps:()->Boolean):Boolean {
        var success=true
        steps.forEach { step -> val result=runCatching { step() }.getOrDefault(false);success=success && result }
        return success
    }
    fun run(credentials:()->Boolean, browser:((Boolean)->Unit)->Unit, done:(Boolean)->Unit) {
        val credentialsCleared=runCatching { credentials() }.getOrDefault(false)
        val completed=java.util.concurrent.atomic.AtomicBoolean()
        val finish:(Boolean)->Unit={ ok -> if(completed.compareAndSet(false,true)) done(credentialsCleared && ok) }
        try { browser(finish) } catch (_:Exception) { finish(false) }
    }
}
