package com.mirror.app.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mirror.app.core.action
import com.mirror.app.core.insetContent
import com.mirror.app.core.label

class ClockCalendarSettingsActivity : Activity() {
    private lateinit var content: LinearLayout
    private var generation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        PhoneTheme.install(this); super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(content) }); insetContent(content)
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val epoch = ++generation
        content.removeAllViews()
        content.addView(PhoneTheme.appBar(this, "Roster calendar") { finish() })
        if (DepartureRosterSource.select(this) == DepartureSourcePolicy.Source.ECREW) {
            content.addView(label("Roster source is eCrew Roster Link. Calendar access is not needed."))
            content.addView(action("Use Calendar roster source") { ClockSettings.load(this).copy(departureSource = "Calendar").save(this); render() })
            PhoneUi.style(content)
            return
        }
        content.addView(label("Choose the calendar where eCrew exports your roster. Mirror reads it without creating, editing or deleting events. Enable Google Calendar sync for that account in Android settings."))
        content.addView(label("Roster times always use Philippine time. Checks run every 15 minutes, on calendar changes, and at duty end. Android controls when requested Google sync finishes."))
        if (!ClockCalendar.hasPermission(this)) {
            content.addView(action("Allow calendar read access") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 52) })
            content.addView(label("If access was previously denied permanently, enable Calendar permission in Android's app settings for Mirror."))
            content.addView(action("Mirror app settings") {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            })
            return
        }
        val status = label("Loading calendars…"); content.addView(status)
        ClockCalendar.listSources(this) { result ->
            if (isFinishing || isDestroyed || epoch != generation) return@listSources
            result.fold(onSuccess = { sources ->
                status.text = if (sources.isEmpty()) "No calendars are synced on this phone. Add your Google account and enable Calendar sync, then return here." else "Select one roster calendar:"
                sources.forEach { source -> content.addView(action("${source.name}\n${source.account}") {
                    ClockSettings.load(this).copy(schedule = true, departureSource = "Calendar", calendarId = source.id, calendarName = source.name).save(this)
                    finish()
                }) }
            }, onFailure = { status.text = "Calendar unavailable. Check Calendar permission and account sync, then try again." })
        }
        content.addView(action("Open calendar") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build())) }
        })
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 52) render()
    }
}

