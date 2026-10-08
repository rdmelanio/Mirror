package com.mirror.app.phone.roster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

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
    fun clear(c: Context, web: WebView, alive: () -> Boolean, done: () -> Unit) {
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
            if (alive()) { web.clearCache(true); web.clearHistory() }
            CaptureLog.add(c, "STORAGE", "eCrew WebStorage and browser cache cleared")
            done()
        }
        if (remaining == 0) cookiesDone()
        else expired.forEach { cookie -> manager.setCookie(RosterFetcher.ORIGIN, cookie) {
            main.post { remaining--; if (remaining == 0) cookiesDone() }
        } }
    }
    const val CLEANUP_URL = "${RosterFetcher.ORIGIN}/eCrew/__mirror_storage_cleanup__"
    // An app-owned, offline document with the eCrew origin. This is never the Login document.
    // The native site-data API is deliberately avoided: it deletes the entire registrable domain.
    val cleanupDocument = """<!doctype html><meta charset="utf-8"><title>Mirror session cleanup</title>
        <p>Clearing eCrew session data…</p><script>
        (async function(){
          let storage=true,idb=true,workers=true,cache=true;
          try{localStorage.clear();sessionStorage.clear();}catch(e){storage=false;}
          try{if(indexedDB.databases){const dbs=await indexedDB.databases();await Promise.all(dbs.filter(d=>d.name).map(d=>new Promise(resolve=>{const r=indexedDB.deleteDatabase(d.name);r.onsuccess=()=>resolve();r.onerror=r.onblocked=()=>{idb=false;resolve();};})));}else idb=false;}catch(e){idb=false;}
          try{if(navigator.serviceWorker){const registrations=await navigator.serviceWorker.getRegistrations();await Promise.all(registrations.map(r=>r.unregister()));}}catch(e){workers=false;}
          try{if(window.caches)await Promise.all((await caches.keys()).map(k=>caches.delete(k)));}catch(e){cache=false;}
          if(window.MirrorPdf)MirrorPdf.postMessage(JSON.stringify({kind:'storageCleared',storage,idb,workers,cache}));
        })();
        </script>""".trimIndent()
}
