package com.mirror.app.tv

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mirror.app.core.*

internal object AirFeedbackSetup {
    fun show(activity: TvActivity) {
        val prefs=activity.mirrorPreferences()
        AlertDialog.Builder(activity).setTitle("Air Remote feedback")
            .setMessage("${AirFeedbackService.status}\n\nOne-time setup: pair this TV with your Mirror phone under Connection, enable the listener, then allow Display over other apps.\n\nOnly brief activation/command-sent messages appear; no persistent overlay. Android requires a background-service notification. Commands still use Android TV Remote v2.\n\nIf TCL or another app blocks overlays, feedback appears only while Mirror is open; navigation can still work. Reopen Mirror once after force-stop. Global visibility must be checked on your TV.")
            .setPositiveButton(if(prefs.getBoolean("airFeedback",false)) "Disable listener" else "Enable listener") { _,_ ->
                val enabled=!prefs.getBoolean("airFeedback",false);prefs.edit().putBoolean("airFeedback",enabled).apply()
                if(enabled) AirFeedbackService.resume(activity) else activity.stopService(Intent(activity,AirFeedbackService::class.java))
            }
            .setNeutralButton("Overlay permission") { _,_ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${activity.packageName}"))) }
                    .onFailure { activity.toast("TCL does not expose overlay permission. Feedback is in-app only; check TV app settings.",5000) }
            }.setNegativeButton("Close",null).show()
    }
}
