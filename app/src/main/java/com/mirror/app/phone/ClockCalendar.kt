package com.mirror.app.phone

import android.Manifest
import android.accounts.Account
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Reads the selected synced calendar only. No event insert/update/delete operations. */
class ClockCalendar(private val context: Context, private val changed: () -> Unit) {
    data class Source(val id: Long, val name: String, val account: String, val type: String)
    data class Snapshot(val calendarId: Long = -1, val duties: List<ClockRoster.Duty> = emptyList(),
                        val checkedAt: Long = 0, val windowStart: Long = 0, val windowEnd: Long = 0,
                        val error: String? = null, val loading: Boolean = true, val offline: Boolean = false)
    var snapshot = Snapshot(); private set
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var registered = false
    private var generation = 0
    private var selected = -1L
    private var busy = false
    private var pending = false
    private var pendingSync = false
    private val periodic = Runnable { refresh(true) }
    private val boundary = Runnable { changed(); refresh(true) }
    private val observed = Runnable { refresh(false) }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(observed); handler.postDelayed(observed, 750)
        }
    }
    fun start(settings: ClockSettings) {
        if (!settings.schedule && !settings.departureEnabled) { stop(); snapshot = Snapshot(loading = false); return }
        if (active && selected == settings.calendarId) return
        stop(); active = true; selected = settings.calendarId
        snapshot = Snapshot(calendarId = selected)
        if (!hasPermission(context)) { snapshot = snapshot.copy(loading = false, error = "Allow calendar access in Clock settings"); changed(); return }
        if (selected < 0) { snapshot = snapshot.copy(loading = false, error = "Choose a calendar in Clock settings"); changed(); return }
        try { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer); registered = true }
        catch (_: SecurityException) { snapshot = snapshot.copy(loading = false, error = "Calendar access unavailable") }
        refresh(true)
    }
    fun stop() {
        active = false; generation++; busy = false; pending = false; pendingSync = false
        handler.removeCallbacksAndMessages(null)
        if (registered) { context.contentResolver.unregisterContentObserver(observer); registered = false }
    }
    fun refresh(sync: Boolean = true) {
        if (!active || selected < 0) return
        if (!hasPermission(context)) {
            snapshot = Snapshot(calendarId = selected, error = "Allow calendar access in Clock settings", loading = false)
            changed(); return
        }
        if (busy) { pending = true; pendingSync = pendingSync || sync; return }
        busy = true
        val epoch = generation; val id = selected
        val old = snapshot
        worker.execute {
            val cached = if (old.checkedAt == 0L) readCache(context, id) else old
            val result = runCatching {
                val source = sources(context).firstOrNull { it.id == id } ?: throw NoSuchElementException("Selected calendar is unavailable")
                if (sync && source.type != CalendarContract.ACCOUNT_TYPE_LOCAL && source.account.isNotBlank()) {
                    // This asks Android's sync adapter to fetch changes; completion remains OS-controlled.
                    runCatching { ContentResolver.requestSync(Account(source.account, source.type), CalendarContract.AUTHORITY,
                        Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) }) }
                }
                readEvents(context, id)
            }
            val value = result.getOrElse {
                if (it is SecurityException) Snapshot(calendarId = id, error = "Calendar permission unavailable", loading = false)
                else if (it is NoSuchElementException) Snapshot(calendarId = id, error = "Selected calendar unavailable · choose calendar", loading = false)
                else cached.copy(calendarId = id, error = "Calendar unavailable · cached roster", loading = false)
            }
            // Cache only successful provider reads. A failed read must not erase a usable offline roster.
            if (result.isSuccess) runCatching { writeCache(context, value) }
            handler.post {
                if (!active || epoch != generation || id != selected) return@post
                busy = false; snapshot = value; DepartureAlerts.accept(context, value); changed()
                handler.removeCallbacks(periodic); handler.postDelayed(periodic, 15 * 60_000L)
                scheduleBoundary()
                if (pending) { val requestSync = pendingSync; pending = false; pendingSync = false; refresh(requestSync) }
            }
        }
    }
    fun timeChanged() { if (active) { changed(); refresh(true) } }
    private fun scheduleBoundary() {
        handler.removeCallbacks(boundary)
        val now = System.currentTimeMillis()
        handler.postDelayed(boundary, (ClockRoster.nextBoundary(snapshot.duties, now) - now).coerceAtLeast(1))
    }
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        fun hasPermission(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        fun listSources(context: Context, callback: (Result<List<Source>>) -> Unit) {
            val app = context.applicationContext
            worker.execute { val result = runCatching { sources(app) }; Handler(Looper.getMainLooper()).post { callback(result) } }
        }
        private fun sources(context: Context): List<Source> {
            if (!hasPermission(context)) throw SecurityException("Calendar access is required")
            val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE)
            val result = mutableListOf<Source>()
            val cursor = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, null, null, null)
                ?: throw IllegalStateException("Calendar provider unavailable")
            cursor.use { while (it.moveToNext()) result += Source(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3).orEmpty()) }
            return result.sortedBy { it.name.lowercase() }
        }
        internal fun readForDeparture(context: Context, id: Long, sync: Boolean): Snapshot {
            val source = sources(context).firstOrNull { it.id == id } ?: throw NoSuchElementException("Choose a calendar")
            if (sync && source.type != CalendarContract.ACCOUNT_TYPE_LOCAL && source.account.isNotBlank()) runCatching {
                ContentResolver.requestSync(Account(source.account, source.type), CalendarContract.AUTHORITY,
                    Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) })
            }
            return readEvents(context, id)
        }
        internal fun readEvents(context: Context, id: Long): Snapshot {
            val now = System.currentTimeMillis()
            val today = java.time.Instant.ofEpochMilli(now).atZone(ClockRoster.zone).toLocalDate()
            val from = today.minusDays(7).atStartOfDay(ClockRoster.zone).toInstant().toEpochMilli()
            val until = today.plusDays(35).atStartOfDay(ClockRoster.zone).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from); ContentUris.appendId(it, until)
            }.build()
            val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY)
            val duties = mutableListOf<ClockRoster.Duty>()
            val cursor = context.contentResolver.query(uri, columns,
                "${CalendarContract.Instances.CALENDAR_ID}=? AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS}!=?)",
                arrayOf(id.toString(), CalendarContract.Events.STATUS_CANCELED.toString()), CalendarContract.Instances.BEGIN + " ASC")
                ?: throw IllegalStateException("Calendar provider unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    check(duties.size < 2000) { "Calendar contains too many duties" }
                    val event = ClockRoster.Event(it.getLong(0), it.getString(1).orEmpty().take(500), it.getString(2).orEmpty().take(32_000),
                        it.getLong(3), it.getLong(4), it.getInt(5) != 0)
                    duties += ClockRoster.parse(event)
                }
            }
            val network = context.getSystemService(android.net.ConnectivityManager::class.java)
            val online = network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            return Snapshot(id, duties.distinctBy { Triple(it.id, it.start, it.end) }, now, from, until, loading = false, offline = !online)
        }
        private fun cache(context: Context) = context.getSharedPreferences("mirror_roster_cache", Context.MODE_PRIVATE)
        internal fun writeCache(context: Context, snapshot: Snapshot) {
            val entries = JSONArray()
            snapshot.duties.forEach { d -> entries.put(JSONObject().put("id", d.id).put("text", d.text).put("start", d.start)
                .put("end", d.end).put("day", d.day.toString()).put("allDay", d.allDay).put("fallback", d.calendarTimes)) }
            val json = JSONObject().put("calendarId", snapshot.calendarId).put("checkedAt", snapshot.checkedAt)
                .put("windowStart", snapshot.windowStart).put("windowEnd", snapshot.windowEnd).put("entries", entries)
            cache(context).edit().putString("snapshot", json.toString()).apply()
        }
        private fun readCache(context: Context, id: Long): Snapshot = runCatching {
            val j = JSONObject(cache(context).getString("snapshot", "{}")!!)
            require(j.getLong("calendarId") == id)
            val entries = j.getJSONArray("entries")
            require(entries.length() <= 2000)
            val duties = (0 until entries.length()).map { n -> val d = entries.getJSONObject(n)
                ClockRoster.Duty(d.getLong("id"), d.getString("text"), d.getLong("start"), d.getLong("end"),
                    java.time.LocalDate.parse(d.getString("day")), d.getBoolean("allDay"), d.optBoolean("fallback")) }
            Snapshot(id, duties, j.getLong("checkedAt"), j.getLong("windowStart"), j.getLong("windowEnd"), loading = false)
        }.getOrDefault(Snapshot(calendarId = id, loading = false))
    }
}
