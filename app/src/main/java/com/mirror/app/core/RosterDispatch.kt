package com.mirror.app.core

import android.app.Activity
import android.content.*
import android.os.Bundle

/** Manifest entry points check the saved role before loading any roster implementation. */
class RosterDispatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.mirror.app.BuildConfig.ROSTER_ENABLED) return
        if (context.mirrorPreferences().getString("role", null) != "phone") return
        val receiver = Class.forName("com.mirror.app.phone.roster.RosterAlarmReceiver").getDeclaredConstructor().newInstance() as BroadcastReceiver
        receiver.onReceive(context, intent)
    }
}
class RosterShareActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (com.mirror.app.BuildConfig.ROSTER_ENABLED && mirrorPreferences().getString("role", null) == "phone") {
            startActivity(Intent(intent).setClassName(this, "com.mirror.app.phone.roster.RosterImportActivity").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
