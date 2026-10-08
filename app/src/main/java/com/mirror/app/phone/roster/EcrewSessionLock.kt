package com.mirror.app.phone.roster

/** Pure ownership policy. Browser cleanup completes before a lease is released. */
class EcrewSessionCoordinator {
    enum class Owner { INTERACTIVE, WORKER }
    private var holder: Lease? = null
    private var interactiveScreens = 0
    inner class Lease internal constructor(val owner: Owner, internal val yield: () -> Unit) : AutoCloseable {
        fun ownsSession(): Boolean = synchronized(this@EcrewSessionCoordinator) { holder === this }
        override fun close() { synchronized(this@EcrewSessionCoordinator) { if (holder === this) holder = null } }
    }
    inner class Screen internal constructor() : AutoCloseable {
        private var closed = false
        override fun close() { synchronized(this@EcrewSessionCoordinator) { if (!closed) { closed = true; interactiveScreens-- } } }
    }
    fun openScreen(): Screen {
        val worker: Lease?
        synchronized(this) { interactiveScreens++; worker = holder?.takeIf { it.owner == Owner.WORKER } }
        worker?.yield?.invoke() // The worker destroys its WebView and closes its lease synchronously.
        return Screen()
    }
    @Synchronized fun acquireInteractive(): Lease? {
        if (interactiveScreens == 0 || holder != null) return null
        return Lease(Owner.INTERACTIVE) {}.also { holder = it }
    }
    @Synchronized fun acquireWorker(now: Long, lastInteractive: Long, yield: () -> Unit): Lease? {
        if (interactiveScreens > 0 || holder != null || !EcrewRefreshPolicy.allowed(now, lastInteractive)) return null
        return Lease(Owner.WORKER, yield).also { holder = it }
    }
    @Synchronized fun activityOpen() = interactiveScreens > 0
}
object EcrewSessionLock { val coordinator = EcrewSessionCoordinator() }
object EcrewRefreshPolicy {
    const val QUIET_MS = 180_000L
    fun allowed(now: Long, lastInteractive: Long) = lastInteractive == 0L || now - lastInteractive >= QUIET_MS
    fun interval(value: Int) = if (value in listOf(15, 30, 60)) value else 30
    fun initialDelayMinutes(value: Int) = interval(value)
}
