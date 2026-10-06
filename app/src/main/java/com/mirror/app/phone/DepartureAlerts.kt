package com.mirror.app.phone

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.CalendarContract
import org.json.JSONObject
import java.util.concurrent.Executors

/** AlarmManager owns departure deadlines; jobs refresh the local calendar without a permanent service. */
object DepartureAlerts {
    const val FIRE = "com.mirror.app.DEPARTURE_FIRE"
    const val STOP = "com.mirror.app.DEPARTURE_STOP"
    const val EXPIRE = "com.mirror.app.DEPARTURE_EXPIRE"
    private const val PERIODIC = 17001
    internal const val CHANGES = 17002
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    fun prefs(c: Context) = c.getSharedPreferences("mirror_departure_runtime", Context.MODE_PRIVATE)
    fun configuration(s: ClockSettings) = listOf(s.departureEnabled, s.calendarId, s.cautionEnabled, s.warningEnabled,
        s.cautionMinutes, s.warningMinutes, s.warningSeconds, s.excludedDutyCodes, s.allowCalendarStartAlerts, s.cautionSound, s.warningSound)
    fun exactAllowed(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationsAllowed(c: Context): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (manager.getNotificationChannel(DepartureSoundService.CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) > NotificationManager.IMPORTANCE_NONE
    }
    private fun alarmIntent(c: Context, kind: DeparturePlan.Kind, token: String = ""): PendingIntent = PendingIntent.getBroadcast(c,
        17010 + kind.level, Intent(c, DepartureReceiver::class.java).setAction(FIRE).putExtra("token", token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun configure(context: Context, complete: (() -> Unit)? = null) {
        val c = context.applicationContext; generation++
        val s = ClockSettings.load(c); val jobs = c.getSystemService(JobScheduler::class.java)
        val alarm = c.getSystemService(AlarmManager::class.java)
        DeparturePlan.Kind.entries.forEach { alarm.cancel(alarmIntent(c, it)) }
        if (!s.departureEnabled) {
            jobs.cancel(PERIODIC); jobs.cancel(CHANGES); acknowledge(c)
            prefs(c).edit().putString("status", "Departure alerts are off").remove("next").apply(); complete?.invoke(); return
        }
        if (!ClockCalendar.hasPermission(c) || s.calendarId < 0) {
            jobs.cancel(PERIODIC); jobs.cancel(CHANGES); acknowledge(c)
            prefs(c).edit().putString("status", "Choose a roster calendar and allow calendar read access").remove("next").apply()
            complete?.invoke(); return
        }
        jobs.schedule(JobInfo.Builder(PERIODIC, ComponentName(c, DepartureRefreshJob::class.java))
            .setPeriodic(15 * 60_000L).setPersisted(true).build())
        watchChanges(c)
        refresh(c, complete)
    }
    internal fun watchChanges(c: Context) {
        if (!ClockSettings.load(c).departureEnabled) return
        c.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(CHANGES, ComponentName(c, DepartureRefreshJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000).build())
    }
    fun refresh(context: Context, complete: (() -> Unit)? = null, fireToken: String? = null, sync: Boolean = fireToken == null) {
        val c = context.applicationContext; val s = ClockSettings.load(c); val epoch = generation
        worker.execute {
            val result = runCatching { ClockCalendar.readForDeparture(c, s.calendarId, sync) }
            main.post {
                try {
                    if (epoch != generation || configuration(s) != configuration(ClockSettings.load(c))) return@post
                    result.fold(onSuccess = { snapshot ->
                        ClockCalendar.writeCache(c, snapshot)
                        accept(c, snapshot, fireToken != null)
                    }, onFailure = {
                        // Never turn a stale, failed provider read into a new departure alarm.
                        prefs(c).edit().putString("status", "Calendar refresh failed · existing alarms will recheck before sounding").apply()
                    })
                } finally { complete?.invoke() }
            }
        }
    }
    fun delivered(c: Context): Map<String, Int> = runCatching {
        val j = JSONObject(prefs(c).getString("delivered", "{}")!!)
        j.keys().asSequence().associateWith { j.optInt(it) }
    }.getOrDefault(emptyMap())
    private fun mark(c: Context, a: DeparturePlan.Alert) {
        val values = delivered(c).toMutableMap()
        values[a.occurrence] = maxOf(values[a.occurrence] ?: 0, a.kind.level)
        // Bounded history; keys include the recurring occurrence's Philippine date.
        val j = JSONObject(); values.entries.sortedByDescending { it.key.substringAfterLast(':') }.take(500).forEach { j.put(it.key, it.value) }
        prefs(c).edit().putString("delivered", j.toString()).commit()
    }
    fun accept(c: Context, snapshot: ClockCalendar.Snapshot, fromAlarm: Boolean = false) {
        val s = ClockSettings.load(c)
        if (!s.departureEnabled || snapshot.calendarId != s.calendarId || snapshot.loading) return
        if (snapshot.error != null || snapshot.checkedAt == 0L) return
        val now = System.currentTimeMillis()
        var alerts = DeparturePlan.alerts(snapshot.duties, s, now, delivered(c))
        val active = active(c)
        // Remove/revise stale screens when a synced duty is cancelled or moved. Tests are independent.
        if (active != null && !active.test) {
            val current = snapshot.duties.firstOrNull { "${s.calendarId}:${it.id}:${it.day}" == active.occurrence }
            if (current == null || !DeparturePlan.eligible(current, s) || current.start != active.reporting ||
                (active.kind == DeparturePlan.Kind.CAUTION && !s.cautionEnabled) || (active.kind == DeparturePlan.Kind.WARNING && !s.warningEnabled)) acknowledge(c)
            else if (active.kind == DeparturePlan.Kind.CAUTION) {
                val warningAt = if (s.warningEnabled) current.start - s.warningMinutes * 60_000L else current.start
                if (warningAt != active.warningAt) {
                    val stored = JSONObject(prefs(c).getString("active", "{}")!!)
                    prefs(c).edit().putString("active", stored.put("warningAt", warningAt).toString()).apply()
                }
            }
        }
        if (!exactAllowed(c) || !notificationsAllowed(c)) {
            cancelFuture(c)
            prefs(c).edit().putString("status", "Setup needed: allow precise alarms and alarm notifications").remove("next").apply(); return
        }
        val overdue = DeparturePlan.overdue(alerts, now)
        if (overdue != null) {
            // A refresh/job can start audio only through an immediate exact alarm (FGS exemption).
            if (fromAlarm) {
                mark(c, overdue); show(c, overdue)
                alerts = DeparturePlan.alerts(snapshot.duties, s, now, delivered(c))
            } else {
                val open = PendingIntent.getActivity(c, 17020, Intent(c, PhoneSettingsActivity::class.java).putExtra("section", "alarms"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                try { c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(now + 500, open),
                    alarmIntent(c, overdue.kind, overdue.token)) }
                catch (_: SecurityException) { prefs(c).edit().putString("status", "Allow precise alarms to schedule departure alerts").apply(); return }
            }
        }
        val descriptions = mutableListOf<String>()
        for (kind in DeparturePlan.Kind.entries) {
            val next = DeparturePlan.next(alerts, kind, now)
            // Preserve the immediate overdue alarm until its receiver validates the calendar again.
            if (overdue != null && !fromAlarm && kind == overdue.kind) continue
            val manager = c.getSystemService(AlarmManager::class.java)
            if (next == null) manager.cancel(alarmIntent(c, kind))
            else {
                val show = PendingIntent.getActivity(c, 17020, Intent(c, PhoneSettingsActivity::class.java).putExtra("section", "alarms"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                try { manager.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, show), alarmIntent(c, kind, next.token)) }
                catch (_: SecurityException) { prefs(c).edit().putString("status", "Allow precise alarms to schedule departure alerts").apply(); return }
                descriptions += "${kind.name}: ${stamp(next.at)} · ${next.duty.text} · REPORT ${stamp(next.duty.start)}"
            }
        }
        val missing = snapshot.duties.count { it.start > now && !it.allDay && it.calendarTimes && !s.allowCalendarStartAlerts }
        val status = if (overdue != null && !fromAlarm) "Late calendar update · triggering ${overdue.kind.name.lowercase()}" else
            if (descriptions.isEmpty()) "No upcoming eligible timed duty in the synced calendar" else "Scheduled · calendar checked ${stamp(snapshot.checkedAt)}"
        prefs(c).edit().putString("status", status + if (missing > 0) "\n$missing flight duty/duties missing report/debrief times · skipped" else "")
            .putString("next", descriptions.joinToString("\n")).apply()
    }
    private fun cancelFuture(c: Context) { DeparturePlan.Kind.entries.forEach { c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c, it)) } }
    fun stamp(time: Long): String = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm 'PHT'", java.util.Locale.ENGLISH)
        .format(java.time.Instant.ofEpochMilli(time).atZone(ClockRoster.zone))
    data class Active(val occurrence: String, val token: String, val kind: DeparturePlan.Kind, val duty: String,
                      val reporting: Long, val warningAt: Long, val started: Long, val expires: Long, val test: Boolean)
    fun active(c: Context): Active? {
        val raw = prefs(c).getString("active", null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val j = JSONObject(raw)
            Active(j.getString("occurrence"), j.getString("token"), DeparturePlan.Kind.valueOf(j.getString("kind")), j.getString("duty"),
                j.getLong("reporting"), j.getLong("warningAt"), j.getLong("started"), j.getLong("expires"), j.getBoolean("test"))
        }.getOrNull()?.takeIf { it.expires > System.currentTimeMillis() }
    }
    fun test(c: Context, kind: DeparturePlan.Kind) {
        val now = System.currentTimeMillis(); val s = ClockSettings.load(c)
        show(c, DeparturePlan.Alert("test", kind, now,
            ClockRoster.Duty(-1, "TEST · no roster alarm changed", now + s.cautionMinutes * 60_000L, now + 4 * 3_600_000L,
                java.time.Instant.ofEpochMilli(now).atZone(ClockRoster.zone).toLocalDate(), false)), true)
    }
    private fun show(c: Context, a: DeparturePlan.Alert, test: Boolean = false) {
        val previous = active(c)
        if (test && previous != null && !previous.test) return
        prefs(c).edit().remove("audioStatus").apply()
        // Never replace an unacknowledged warning with a lower-priority caution.
        if (previous != null && !previous.test && !test && previous.kind.level > a.kind.level) return
        val now = System.currentTimeMillis(); val s = ClockSettings.load(c)
        val expires = if (test) now + 10 * 60_000L else DeparturePlan.expires(a.kind, now, a.duty.start)
        val token = "${a.token}:$now"
        val j = JSONObject().put("occurrence", a.occurrence).put("token", token).put("kind", a.kind.name).put("duty", a.duty.text)
            .put("reporting", a.duty.start).put("warningAt", if (s.warningEnabled) a.duty.start - s.warningMinutes * 60_000L else a.duty.start)
            .put("started", now).put("expires", expires).put("test", test)
        prefs(c).edit().putString("active", j.toString()).commit()
        val expiry = PendingIntent.getBroadcast(c, 17030, Intent(c, DepartureReceiver::class.java).setAction(EXPIRE).putExtra("token", token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { c.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expires, expiry) }
        catch (_: SecurityException) { /* Visible hosts also expire the screen; receiver handles expiry when allowed. */ }
        try { c.startForegroundService(Intent(c, DepartureSoundService::class.java).putExtra("token", token)) }
        catch (_: RuntimeException) {
            DepartureSoundService.notifyAlert(c)
            prefs(c).edit().putString("status", "Alert shown · Android blocked background audio; check alarm setup").apply()
        }
    }
    fun acknowledge(c: Context) {
        prefs(c).edit().remove("active").apply()
        c.stopService(Intent(c, DepartureSoundService::class.java))
        c.getSystemService(NotificationManager::class.java).cancel(DepartureSoundService.NOTIFICATION)
        c.getSystemService(AlarmManager::class.java).cancel(PendingIntent.getBroadcast(c, 17030,
            Intent(c, DepartureReceiver::class.java).setAction(EXPIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}

class DepartureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DepartureAlerts.STOP -> DepartureAlerts.acknowledge(context)
            DepartureAlerts.EXPIRE -> {
                val stored = DepartureAlerts.prefs(context).getString("active", "").orEmpty()
                if (runCatching { JSONObject(stored).optString("token") }.getOrNull() == intent.getStringExtra("token")) DepartureAlerts.acknowledge(context)
            }
            DepartureAlerts.FIRE -> {
                val pending = goAsync()
                DepartureAlerts.refresh(context, { pending.finish() }, intent.getStringExtra("token") ?: "")
            }
            else -> { val pending = goAsync(); DepartureAlerts.configure(context) { pending.finish() } }
        }
    }
}
class DepartureRefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        DepartureAlerts.refresh(this, { jobFinished(params, false); DepartureAlerts.watchChanges(this) }, sync = params.jobId != DepartureAlerts.CHANGES)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
