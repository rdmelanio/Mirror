package com.mirror.app.phone.roster

import android.app.Activity
import android.view.WindowManager

object RosterPrivacy {
    fun apply(activity: Activity) {
        if (RosterStore.prefs(activity).getBoolean("blockScreenshots", false)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
