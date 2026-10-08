package com.mirror.app.phone.roster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast

enum class EcrewEngine(val label: String) {
    FIREFOX("Firefox (recommended)"), WEBVIEW("Android WebView");
    companion object {
        fun selected(c: Context) = if (RosterStore.prefs(c).getString("engine", null) == WEBVIEW.name) WEBVIEW else FIREFOX
        fun automatic(c: Context) = selected(c) == FIREFOX
    }
}
interface EcrewEngineBrowser : EcrewBrowser {
    val view: View?
    fun start()
    fun back(): Boolean
    fun logoutFromTap()
    fun probeFromTap()
}
object RosterEntry {
    fun open(a: Activity) {
        if (com.mirror.app.BuildConfig.ROSTER_ENABLED) a.startActivity(Intent(a, RosterActivity::class.java))
        else Toast.makeText(a, "Install mirror-phone.apk for Roster Link", Toast.LENGTH_LONG).show()
    }
}
