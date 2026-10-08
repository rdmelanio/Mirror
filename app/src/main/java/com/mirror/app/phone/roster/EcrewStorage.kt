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
    fun clearLocal(c: Context, done: () -> Unit) {
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
        fun cookiesDone() {
            manager.flush()
            WebStorage.getInstance().deleteOrigin(RosterFetcher.ORIGIN)
            CaptureLog.add(c, "STORAGE", "local eCrew cookies and WebStorage cleared; no navigation")
            done()
        }
        if (remaining == 0) cookiesDone()
        else expired.forEach { cookie -> manager.setCookie(RosterFetcher.ORIGIN, cookie) {
            main.post { remaining--; if (remaining == 0) cookiesDone() }
        } }
    }
}
