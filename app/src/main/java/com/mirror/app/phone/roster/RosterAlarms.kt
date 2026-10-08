package com.mirror.app.phone.roster

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.mirror.app.R
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter

object RosterNotices {
    fun open(c: Context) = PendingIntent.getActivity(c, 601, Intent(c, ECrewActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun post(c: Context, id: Int, title: String, body: String, fullScreen: PendingIntent? = null) {
        if (!RosterStore.phone(c)) return
        val manager = c.getSystemService(NotificationManager::class.java)
        val channel = if (fullScreen == null) "roster-link" else "roster-alarm"
        manager.createNotificationChannel(NotificationChannel(channel, if (fullScreen == null) "Roster Link" else "Roster alarms", if (fullScreen == null) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE; if (fullScreen != null) { setSound(null, null); enableVibration(true) } })
        val n = Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_mirror).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror roster notification").build())
            .setContentIntent(fullScreen ?: open(c)).setAutoCancel(true)
        if (fullScreen != null) n.setFullScreenIntent(fullScreen, true).setCategory(Notification.CATEGORY_ALARM)
        if (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) manager.notify(id, n.build())
    }
    fun pending(c: Context) {
        val p = RosterStore.prefs(c)
        if (System.currentTimeMillis() - p.getLong("pendingNotice", 0) > 6 * 3_600_000) {
            post(c, 603, "Crew scheduling changed your roster — open eCrew to review & confirm", "Confirmation is always your manual action")
            p.edit().putLong("pendingNotice", System.currentTimeMillis()).apply()
        }
        p.edit().putBoolean("pendingChanges", true).apply()
    }
}
object RosterAlarms {
    fun definitions(c: Context): List<RosterAlarm> {
        val raw = RosterStore.prefs(c).getString("alarms", null) ?: return listOf(RosterAlarm(1))
        return runCatching { val a = JSONArray(raw); (0 until a.length()).map { val j = a.getJSONObject(it); RosterAlarm(j.getInt("id"), j.getString("label"), j.getInt("offset").coerceIn(15, 240), j.getBoolean("enabled")) }.take(5) }.getOrDefault(listOf(RosterAlarm(1)))
    }
    fun save(c: Context, values: List<RosterAlarm>) {
        val a = JSONArray(); values.take(5).forEach { a.put(JSONObject().put("id", it.id).put("label", it.label.take(40)).put("offset", it.offset.coerceIn(15, 240) / 5 * 5).put("enabled", it.enabled)) }
        RosterStore.prefs(c).edit().putString("alarms", a.toString()).apply(); reschedule(c)
    }
    fun eligible(c: Context, d: Duty): Boolean {
        val default = d.type == DutyType.FLIGHT || d.code == "AS" || (d.type == DutyType.OTHER && d.reportInstant != null)
        return d.reportInstant != null && RosterStore.prefs(c).getBoolean("type-${if (d.type == DutyType.FLIGHT) "FLIGHT" else d.code}", default)
    }
    private fun intent(c: Context, key: String) = Intent(c, RosterAlarmReceiver::class.java).setAction("com.mirror.app.ROSTER_ALARM").setData(android.net.Uri.parse("mirror-roster://alarm/${android.net.Uri.encode(key)}"))
    private fun operation(c: Context, key: String) = PendingIntent.getBroadcast(c, 0, intent(c, key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stored(c: Context) = runCatching { JSONObject(RosterStore.prefs(c).getString("scheduled", "{}").orEmpty()) }.getOrDefault(JSONObject())
    fun cancel(c: Context) {
        val m = c.getSystemService(AlarmManager::class.java); val old = stored(c)
        old.keys().forEach { val pi = operation(c, it); m.cancel(pi); pi.cancel() }
        RosterStore.prefs(c).edit().remove("scheduled").apply()
    }
    @Synchronized fun reschedule(c: Context) {
        val old = stored(c); cancel(c)
        if (!RosterStore.phone(c)) return
        val roster = RosterStore.load(c) ?: return
        val p = RosterStore.prefs(c); val skipped = p.getStringSet("skipped", emptySet()).orEmpty()
        val now = Instant.now(); val alarmManager = c.getSystemService(AlarmManager::class.java)
        val plans = RosterAlarmPlan.plan(roster.duties, definitions(c), { eligible(c, it) }, skipped).filter { it.duty.reportInstant!! > now }
        val scheduled = JSONObject()
        plans.forEach { plan ->
            val deliveredKey = "${plan.key}:${plan.at}"
            val delivered = p.getStringSet("delivered", emptySet()).orEmpty()
            if (deliveredKey in delivered) return@forEach
            if (plan.at <= now) {
                RosterNotices.post(c, 610 + plan.alarm.id, plan.alarm.label, "New duty: alarm time has passed · report ${plan.duty.reportLocal?.toLocalTime()}", screen(c, plan))
                p.edit().putStringSet("delivered", delivered + deliveredKey).apply()
            } else if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                try {
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(plan.at.toEpochMilli(), screen(c, plan)), operation(c, plan.key))
                    scheduled.put(plan.key, plan.at.toString())
                    if (old.has(plan.key) && old.getString(plan.key) != plan.at.toString()) {
                        val stamp = plan.at.atZone(AirportZones.zone(plan.duty.legs.firstOrNull()?.depApt ?: "MNL")).format(DateTimeFormatter.ofPattern("HH:mm"))
                        RosterNotices.post(c, 620 + plan.alarm.id, "${plan.alarm.label} moved to $stamp", "Report time changed")
                    }
                } catch (_: SecurityException) { CaptureLog.add(c, "ALARM", "exact alarm permission unavailable") }
            } else CaptureLog.add(c, "ALARM", "exact alarm permission needed")
        }
        p.edit().putString("scheduled", scheduled.toString()).apply()
    }
    fun find(c: Context, key: String): PlannedAlarm? = RosterStore.load(c)?.let { r ->
        RosterAlarmPlan.plan(r.duties, definitions(c), { eligible(c, it) }, RosterStore.prefs(c).getStringSet("skipped", emptySet()).orEmpty()).find { it.key == key }
    }
    fun screen(c: Context, plan: PlannedAlarm): PendingIntent = PendingIntent.getActivity(c, 0,
        Intent(c, RosterAlarmActivity::class.java).setData(android.net.Uri.parse("mirror-roster://screen/${android.net.Uri.encode(plan.key)}")).putExtra("key", plan.key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun skipNext(c: Context) {
        val r = RosterStore.load(c) ?: return
        val next = RosterAlarmPlan.plan(r.duties, definitions(c).filter { it.label == "PREPARE" }, { eligible(c, it) }).filter { it.duty.reportInstant!! > Instant.now() }.minByOrNull { it.at } ?: return
        val p = RosterStore.prefs(c); p.edit().putStringSet("skipped", p.getStringSet("skipped", emptySet()).orEmpty() + next.key).apply(); reschedule(c)
    }
    fun snooze(c: Context, plan: PlannedAlarm) {
        val m = c.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !m.canScheduleExactAlarms()) return
        val i = intent(c, plan.key).putExtra("snooze", true)
        m.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 600_000, screen(c, plan)), PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}
class RosterAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!RosterStore.phone(c)) { RosterWork.cancel(c); RosterAlarms.cancel(c); return }
        if (i.action != "com.mirror.app.ROSTER_ALARM") { RosterAlarms.reschedule(c); RosterWork.configure(c); return }
        val key = i.data?.lastPathSegment ?: return
        val plan = RosterAlarms.find(c, key) ?: return
        if (plan.duty.reportInstant!! <= Instant.now()) return
        val p = RosterStore.prefs(c); val deliveredKey = "${plan.key}:${plan.at}"
        if (!i.getBooleanExtra("snooze", false)) {
            if (deliveredKey in p.getStringSet("delivered", emptySet()).orEmpty()) return
            val scheduled = JSONObject(p.getString("scheduled", "{}").orEmpty())
            if (scheduled.optString(key) != plan.at.toString()) return
            p.edit().putStringSet("delivered", p.getStringSet("delivered", emptySet()).orEmpty() + deliveredKey).apply()
        }
        RosterNotices.post(c, 630 + plan.alarm.id, "MASTER CAUTION · ${plan.alarm.label}", "Report ${plan.duty.reportLocal?.toLocalTime()}", RosterAlarms.screen(c, plan))
        c.startForegroundService(Intent(c, RosterSoundService::class.java).putExtra("key", key))
    }
}
