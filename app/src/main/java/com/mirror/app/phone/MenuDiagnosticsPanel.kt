package com.mirror.app.phone

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import com.mirror.app.core.*

object MenuDiagnosticsPanel {
    fun build(a: Activity, content: LinearLayout) {
        content.addView(a.label("Diagnostics", 24f))
        content.addView(a.label("Developer menu options are hidden by default. Clock and alarm styling is independent of the menu theme.", 15f))
        content.addView(PhoneTheme.switch(a).apply {
            text = "Developer menu options"; isChecked = PhoneTheme.developer(a)
            setOnCheckedChangeListener { _, on -> PhoneTheme.setDeveloper(a, on); a.recreate() }
        })
        if (PhoneTheme.developer(a)) content.addView(PhoneTheme.switch(a).apply {
            text = "Classic ECAM menus"; isChecked = PhoneTheme.classic(a)
            setOnCheckedChangeListener { _, on -> PhoneTheme.setClassic(a, on); a.recreate() }
        })
        if (com.mirror.app.BuildConfig.ROSTER_ENABLED) content.addView(a.action("Capture log") { a.startActivity(Intent(a, com.mirror.app.phone.roster.RosterLogActivity::class.java)) })
    }
}
