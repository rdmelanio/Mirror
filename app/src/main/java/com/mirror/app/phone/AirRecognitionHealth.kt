package com.mirror.app.phone

import java.util.Locale

internal object AirRecognitionStatus {
    fun claiming(gestures: String, progressMs: Long): String =
        gestures + " · Claiming %.1f/2.0 seconds".format(Locale.US, progressMs.coerceIn(0,2000)/1000.0)
}

internal class AirModelUnavailable(cause: Throwable): RuntimeException(cause.message, cause)

/** A single worker owns this boundary. Frame errors retry automatically, at most once per 30s after repeated failures. */
internal class AirRecognitionHealth(private val failureClock:(()->Long)?=null, private val onFailure: () -> Unit) {
    @Volatile var unavailable=false; private set
    var failures=0; private set
    @Volatile var retryAt=0L; private set
    var lastError=""; private set
    fun ready(now:Long)=!unavailable && now>=retryAt
    fun reset() { unavailable=false;failures=0;retryAt=0;lastError="" }
    fun run(now:Long, frame:()->Unit):Boolean {
        if(!ready(now)) return false
        try {
            frame();failures=0;retryAt=0
            if(lastError.isNotEmpty() && !lastError.startsWith("Recovered")) lastError="Recovered · $lastError"
            return true
        } catch(e:Exception) {
            unavailable=e is AirModelUnavailable
            fail(failureClock?.invoke() ?: now,e)
        } catch(e:LinkageError) {
            unavailable=true;fail(failureClock?.invoke() ?: now,e)
        }
        return false
    }
    private fun fail(now:Long,e:Throwable) {
        failures=(failures+1).coerceAtMost(30)
        val delay=(500L shl (failures-1).coerceAtMost(6)).coerceAtMost(30_000)
        retryAt=now+delay
        lastError="${e.javaClass.simpleName}: ${e.message ?: "no detail"} · failure $failures · " +
            if(unavailable) "model unavailable; toggle Air Remote to retry" else "retry in ${delay} ms"
        onFailure()
    }
}
