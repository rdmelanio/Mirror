package com.mirror.app.phone.roster

import android.content.Context
import android.os.*
import android.webkit.WebView
import androidx.work.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RosterWork {
    fun configure(c: Context) {
        if (!RosterStore.phone(c)) { cancel(c); return }
        val p = RosterStore.prefs(c)
        if (!p.getBoolean("linked", false) || p.getBoolean("expired", false)) { cancel(c); return }
        val minutes = p.getInt("interval", 30).let { if (it in listOf(15, 30, 60)) it else 30 }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("roster-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RosterWorker>(minutes.toLong(), TimeUnit.MINUTES).setConstraints(constraints).build())
        val manager = WorkManager.getInstance(c)
        manager.cancelAllWorkByTag("roster-report")
        val now = System.currentTimeMillis()
        RosterStore.load(c)?.duties?.mapNotNull { it.reportInstant }?.distinct()?.forEach { report ->
            val at = report.toEpochMilli() - 2 * 3_600_000
            if (at > now) manager.enqueueUniqueWork("roster-report-$report", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RosterWorker>().addTag("roster-report").setInitialDelay(at - now, TimeUnit.MILLISECONDS).setConstraints(constraints).build())
        }
    }
    fun onOpen(c: Context) {
        if (!RosterStore.phone(c)) return
        val p = RosterStore.prefs(c)
        if (p.getBoolean("linked", false) && !p.getBoolean("expired", false) && System.currentTimeMillis() - p.getLong("lastSuccess", 0) > 600_000)
            WorkManager.getInstance(c).enqueueUniqueWork("roster-open", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RosterWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun cancel(c: Context) {
        val w = WorkManager.getInstance(c); w.cancelUniqueWork("roster-periodic"); w.cancelUniqueWork("roster-open"); w.cancelAllWorkByTag("roster-report")
    }
}
class RosterWorker(c: Context, params: WorkerParameters) : Worker(c, params) {
    private var fetcher: RosterFetcher? = null
    override fun doWork(): Result {
        if (!RosterStore.phone(applicationContext) || !RosterStore.prefs(applicationContext).getBoolean("linked", false) || RosterStore.prefs(applicationContext).getBoolean("expired", false)) return Result.success()
        val latch = CountDownLatch(1); val main = Handler(Looper.getMainLooper())
        main.post {
            if (isStopped) { latch.countDown(); return@post }
            try {
                val web = WebView(applicationContext)
                web.layout(0, 0, 1280, 800)
                fetcher = RosterFetcher(applicationContext, web, true) { _ ->
                    fetcher?.destroy(); fetcher = null; latch.countDown()
                }.also { it.start() }
            } catch (_: Exception) { CaptureLog.add(applicationContext, "LOAD_DASHBOARD", "WebView unavailable"); latch.countDown() }
        }
        try { latch.await(90, TimeUnit.SECONDS) } finally { main.post { fetcher?.destroy(); fetcher = null } }
        // Periodic schedule provides the next attempt. Expiration cancels it; no retry loop.
        return Result.success()
    }
    override fun onStopped() { Handler(Looper.getMainLooper()).post { fetcher?.destroy(); fetcher = null } }
}
