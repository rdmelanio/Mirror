package com.mirror.app.phone.roster

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

enum class EcrewBrowserMode(val label: String) {
    NORMAL("Normal"), PLAIN("Plain"), CLEAN("Clean");
    val automaticScripts get() = this == NORMAL
    val automaticStorageCleanup get() = this == NORMAL
    companion object {
        // New selector defaults to Clean even when the obsolete Plain switch was unset/false.
        fun select(saved: String?) = entries.firstOrNull { it.name == saved } ?: CLEAN
        fun changed(previous: String?, current: EcrewBrowserMode) = previous != null && previous != current.name
    }
}
object EcrewBrowserModes {
    fun selected(c: Context) = EcrewBrowserMode.select(RosterStore.prefs(c).getString("browserMode", null))
    fun save(c: Context, mode: EcrewBrowserMode) {
        RosterStore.prefs(c).edit().putString("browserMode", mode.name).remove("plainBrowser").apply()
    }
    fun opened(c: Context, mode: EcrewBrowserMode): Boolean {
        val p = RosterStore.prefs(c)
        val previous = p.getString("lastBrowserMode", null) ?: Regex(" (NORMAL|PLAIN|CLEAN) INTERACTIVE ECrewActivity create")
            .findAll(CaptureLog.read(c)).lastOrNull()?.groupValues?.get(1)
            ?: if (p.contains("plainBrowser")) { if (p.getBoolean("plainBrowser", false)) "PLAIN" else "NORMAL" } else null
        p.edit().putString("lastBrowserMode", mode.name).apply()
        return EcrewBrowserMode.changed(previous, mode)
    }
}
object EcrewWebViewInfo {
    fun text(c: Context): String {
        val info = WebViewCompat.getCurrentWebViewPackage(c)
        return if (info == null) "WebView provider unavailable" else "WebView: ${info.packageName} ${info.versionName.orEmpty()}"
    }
}
object EcrewCleanSettings {
    @android.annotation.SuppressLint("RestrictedApi")
    fun apply(c: Context, settings: WebSettings, log: (String) -> Unit) {
        settings.userAgentString = EcrewBrowserIdentity.userAgent(WebSettings.getDefaultUserAgent(c))
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        if (supported) WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        log("X-Requested-With opt-out supported=$supported${if (supported) "; allow-list empty" else "; provider setting unavailable"}")
    }
}
