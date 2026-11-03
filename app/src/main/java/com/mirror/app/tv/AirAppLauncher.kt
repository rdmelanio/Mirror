package com.mirror.app.tv

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.mirror.app.phone.AirApp

/** Explicit TV-only allowlist. Intent submission is ATTEMPTED, never execution confirmation. */
internal object AirAppLauncher {
    fun launch(context:Context,app:AirApp):String {
        return try {
            val pm=context.packageManager
            val intent=app.packages.firstNotNullOfOrNull { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) } ?: return "MISSING"
            if(intent.`package` !in app.packages && intent.component?.packageName !in app.packages) return "FAILED"
            if(!AirFeedbackOverlay.foreground() && !Settings.canDrawOverlays(context)) return "BLOCKED"
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ATTEMPTED"
        } catch(_:SecurityException) { "BLOCKED" } catch(_:RuntimeException) { "FAILED" }
    }
}
