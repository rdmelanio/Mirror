package com.mirror.app.phone.roster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage

/** Only the eCrew origin is selected. Never remove all cookies or all browsing data. */
object EcrewStorage {
    private val cookieUrls = listOf(RosterFetcher.ORIGIN, RosterFetcher.DASHBOARD, "${RosterFetcher.ORIGIN}/eCrew/Login", "${RosterFetcher.ORIGIN}/eCrew/")
    fun cookieCount(): Int = CookieManager.getInstance().getCookie(RosterFetcher.DASHBOARD).orEmpty()
        .split(';').count { it.substringBefore('=').trim().isNotEmpty() }
    fun freshLogin(c: Context) {
        // Native API only: never evaluate JavaScript on Login. Cookies are intentionally retained here.
        WebStorage.getInstance().deleteOrigin(RosterFetcher.ORIGIN)
        CaptureLog.add(c, "STORAGE", "eCrew WebStorage cleared before fresh login")
    }
    fun clearLocal(c: Context, done: (Boolean) -> Unit) {
        val manager = CookieManager.getInstance()
        val names = cookieUrls.flatMap { manager.getCookie(it).orEmpty().split(';') }
            .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
        val paths = listOf("/", "/eCrew", "/eCrew/", "/eCrew/Login", "/eCrew/Login/", "/eCrew/Dashboard", "/eCrew/Dashboard/")
        val expired = names.flatMap { name -> paths.flatMap { path ->
            listOf("", "; Domain=ecrew.cebupacificair.com", "; Domain=.cebupacificair.com").map { domain ->
                "$name=; Max-Age=0; Path=$path$domain; Secure"
            }
        } }
        val main = Handler(Looper.getMainLooper())
        var remaining = expired.size
        var accepted = true
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(success:Boolean) {
            if(!finished.compareAndSet(false,true)) return
            main.removeCallbacksAndMessages(null)
            runCatching { CaptureLog.add(c, "STORAGE", if(success) "local eCrew cookies and WebStorage cleared; no navigation" else "local WebView cleanup failed; retry available") }
            done(success)
        }
        main.postDelayed({ finish(false) }, 5000)
        fun cookiesDone() {
            try {
                manager.flush()
                val storage=WebStorage.getInstance()
                storage.deleteOrigin(RosterFetcher.ORIGIN)
                storage.getOrigins { origins -> main.post {
                    finish(accepted && origins!=null && !origins.containsKey(RosterFetcher.ORIGIN) && cookieUrls.all { manager.getCookie(it).isNullOrBlank() })
                } }
            } catch (_:Exception) { finish(false) }
        }
        if (remaining == 0) cookiesDone()
        else expired.forEach { cookie ->
            try { manager.setCookie(RosterFetcher.ORIGIN, cookie) { success ->
                main.post { accepted=accepted && success;remaining--;if(remaining==0) cookiesDone() }
            } } catch (_:Exception) { accepted=false;remaining--;if(remaining==0) cookiesDone() }
        }
    }
}
