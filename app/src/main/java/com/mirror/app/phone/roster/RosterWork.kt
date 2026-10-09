package com.mirror.app.phone.roster

import android.content.Context
import android.os.*
import androidx.work.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RosterWork {
    fun configure(c: Context) {
        if (!RosterStore.phone(c)) { cancel(c); return }
        val p = RosterStore.prefs(c)
        if (!EcrewEngine.automatic(c) || p.getBoolean("loopPaused", false) || !p.getBoolean("linked", false) || p.getBoolean("expired", false)) { cancel(c); return }
        val minutes = EcrewRefreshPolicy.interval(p.getInt("interval", 30))
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val policy = if (p.getInt("scheduleRevision", 0) != 101 || p.getInt("scheduledInterval", 0) != minutes) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("roster-periodic", policy,
            PeriodicWorkRequestBuilder<RosterWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setInitialDelay(EcrewRefreshPolicy.initialDelayMinutes(minutes).toLong(), TimeUnit.MINUTES).setConstraints(constraints).build())
        p.edit().putInt("scheduleRevision", 101).putInt("scheduledInterval", minutes).apply()
        val manager = WorkManager.getInstance(c); manager.cancelAllWorkByTag("roster-report")
        val now = System.currentTimeMillis()
        RosterStore.load(c)?.duties?.mapNotNull { it.reportInstant }?.distinct()?.forEach { report ->
            val at = report.toEpochMilli() - 2 * 3_600_000
            if (at > now) manager.enqueueUniqueWork("roster-report-$report", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RosterWorker>().addTag("roster-report").setInitialDelay(at - now, TimeUnit.MILLISECONDS).setConstraints(constraints).build())
        }
    }
    fun onOpen(c: Context) {
        if (!RosterStore.phone(c) || EcrewSessionLock.coordinator.activityOpen()) return
        val p = RosterStore.prefs(c); val now = System.currentTimeMillis()
        if (EcrewEngine.automatic(c) && !p.getBoolean("loopPaused", false) && p.getBoolean("linked", false) && !p.getBoolean("expired", false) && now - p.getLong("lastSuccess", 0) > 600_000 && EcrewRefreshPolicy.allowed(now, p.getLong("lastInteractive", 0)))
            WorkManager.getInstance(c).enqueueUniqueWork("roster-open", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RosterWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun cancel(c: Context) {
        val w = WorkManager.getInstance(c); w.cancelUniqueWork("roster-periodic"); w.cancelUniqueWork("roster-open"); w.cancelAllWorkByTag("roster-report")
        RosterStore.prefs(c).edit().remove("scheduleRevision").remove("scheduledInterval").apply()
    }
}
class RosterWorker(c: Context, params: WorkerParameters) : Worker(c, params) {
    private var fetcher: EcrewEngineBrowser? = null
    private var lease: EcrewSessionCoordinator.Lease? = null
    private val latch = CountDownLatch(1)
    private fun close() { fetcher?.destroy(); fetcher = null; lease?.close(); lease = null; latch.countDown() }
    override fun doWork(): Result {
        val c = applicationContext; val p = RosterStore.prefs(c)
        if (!RosterStore.phone(c) || !EcrewEngine.automatic(c) || p.getBoolean("loopPaused", false) || !p.getBoolean("linked", false) || p.getBoolean("expired", false)) return Result.success()
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (isStopped || !EcrewEngine.automatic(c) || p.getBoolean("loopPaused", false) || !p.getBoolean("linked", false) || p.getBoolean("expired", false)) { close(); return@post }
            if (!EcrewFirefox.canRefresh(c)) {
                CaptureLog.add(c, "ENGINE", "background refresh limited: foreground only"); close(); return@post
            }
            val granted = EcrewSessionLock.coordinator.acquireWorker(System.currentTimeMillis(), p.getLong("lastInteractive", 0)) {
                CaptureLog.add(c, "SESSION", "worker yielded to interactive screen"); close()
            }
            if (granted == null) { CaptureLog.add(c, "SESSION", "worker skipped: session busy or interactive cooldown"); close(); return@post }
            lease = granted
            try {
                fetcher = EcrewFirefox.create(c, granted, true) { close() }.also { it.start() }
            } catch (_: Exception) { CaptureLog.add(c, "LOAD_DASHBOARD", "Firefox unavailable"); close() }
        }
        try { latch.await(245, TimeUnit.SECONDS) } finally { main.post { close() } }
        return Result.success()
    }
    override fun onStopped() { Handler(Looper.getMainLooper()).post { close() } }
}

