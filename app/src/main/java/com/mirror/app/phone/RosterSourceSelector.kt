package com.mirror.app.phone

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import com.mirror.app.core.action

/** Both settings destinations write the same existing departureSource preference. */
object RosterSourceSelector {
    fun title(a: Activity) = if (DepartureRosterSource.select(a) == DepartureSourcePolicy.Source.ECREW) "eCrew Roster Link" else "Calendar"
    fun add(a: Activity, parent: LinearLayout, changed: () -> Unit = {}): () -> Unit {
        val options = if (com.mirror.app.BuildConfig.ROSTER_ENABLED) arrayOf("eCrew Roster Link", "Calendar") else arrayOf("Calendar")
        var refresh: () -> Unit = {}
        fun choose() {
            AlertDialog.Builder(a).setTitle("Roster source").setSingleChoiceItems(options, options.indexOf(title(a))) { dialog, index ->
                ClockSettings.load(a).copy(departureSource = if (options[index] == "Calendar") "Calendar" else "eCrew").save(a)
                dialog.dismiss(); refresh(); changed()
            }.show()
        }
        if (PhoneTheme.classic(a)) {
            val button = a.action("", ::choose); parent.addView(button)
            refresh = { button.text = "Roster source: ${title(a)}" }
        } else {
            val row = PhoneTheme.row(a, "Roster source", "Clock face and departure alarms", title(a), ::choose)
            parent.addView(row)
            val trailing = row.getChildAt(1) as TextView
            refresh = { trailing.text = "${title(a)}  ›"; row.contentDescription = "Roster source: ${title(a)}. Clock face and departure alarms" }
        }
        refresh(); return refresh
    }
}
