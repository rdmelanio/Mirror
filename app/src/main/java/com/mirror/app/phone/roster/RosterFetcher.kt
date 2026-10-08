package com.mirror.app.phone.roster

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One leased browser. Login fields and login documents are never inspected with JavaScript. */
class RosterFetcher(private val c: Context, val web: WebView,
    private val lease: EcrewSessionCoordinator.Lease, private val background: Boolean = false,
    private val lifetime: EcrewBrowserLifetime = EcrewBrowserLifetime().apply { create() },
    private val completed: (Boolean) -> Unit = {}) : EcrewBrowser {
    companion object {
        const val ORIGIN = "https://ecrew.cebupacificair.com"
        const val DASHBOARD = "$ORIGIN/eCrew/Dashboard/"
        const val LIMIT = 20 * 1024 * 1024
        fun trusted(url: String?) = url != null && runCatching { val u = Uri.parse(url); u.scheme == "https" && u.host == "ecrew.cebupacificair.com" && (u.port == -1 || u.port == 443) }.getOrDefault(false)
        fun login(url: String?) = url?.contains("/Login", true) == true
    }
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val downloading = AtomicBoolean(false)
    private val attemptedDownloads = mutableSetOf<String>()
    @Volatile private var dead = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var active = false
    @Volatile private var step = "LOAD_DASHBOARD"
    private var since = 0L
    private var nextMonth = false
    private var captured = false
    private var manual = false
    private var autoStarted = false
    private var linkedInDocument = false
    private var lastPdfHash = 0
    private var loggingOut = false
    override val instanceId = java.util.UUID.randomUUID().toString().take(8)
    override val mode = "NORMAL"
    private val owner = if (background) "WORKER" else "INTERACTIVE"
    private val diagnostics = EcrewDiagnostics(c, instanceId, mode, owner) { pauseForLoop() }
    private val logoutGate = EcrewLogoutGate()
    private var paused = false
    private val logoutOrder = EcrewLogoutOrder()
    private var freshStorageCleared = false
    private var terminated = false
    private var clearingStorage = false
    private val logoutTimeout = Runnable {
        if (logoutOrder.timeout(SystemClock.elapsedRealtime())) {
            loggingOut = false
            diagnostics.add("LOGOUT", "Login was not reached within 10 s; storage retained")
            Toast.makeText(c, "eCrew logout did not reach Login. Use eCrew’s logout and try again.", Toast.LENGTH_LONG).show()
        }
    }
    private fun loginShown(ready: Boolean = false) {
        if (loggingOut) {
            if (ready && logoutOrder.loginShown(true, SystemClock.elapsedRealtime())) clearAfterLogout()
        } else if (!RosterStore.prefs(c).getBoolean("linked", false) && !freshStorageCleared) {
            freshStorageCleared = true; EcrewStorage.freshLogin(c)
        }
        expire()
    }
    private val timeout = Runnable { finish(false, "90 s timeout") }
    private val poll = Runnable { drive() }
    private val firstFetch = Runnable { if (allowed() && !active && !login(web.url) && !RosterStore.prefs(c).getBoolean("loopPaused", false)) start() }
    private fun allowed() = !dead && lease.ownsSession() && RosterStore.phone(c)
    private fun captureAllowed() = allowed() && !paused && active && step in listOf("CLICK_PRINT", "CAPTURE_PDF")
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            userAgentString = EcrewBrowserIdentity.userAgent(WebSettings.getDefaultUserAgent(c))
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        configureRequestedWithHeader()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MirrorPdf", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (!allowed() || paused || !mainFrame || !trusted(origin.toString()) || login(web.url) || !trusted(web.url)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > LIMIT * 4 / 3 + 256) return@addWebMessageListener
                val j = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
                when (j.optString("kind")) {
                    "terminated" -> sessionTerminated()
                    "print" -> if (!background && !terminated && !loggingOut && !clearingStorage) {
                        manual = true; active = true; captured = false; nextMonth = false; attemptedDownloads.clear()
                        handler.removeCallbacks(firstFetch); handler.removeCallbacks(timeout); setStep("CAPTURE_PDF")
                        handler.postDelayed(timeout, 90_000); handler.post(poll)
                    }
                    "pdf" -> if (captureAllowed()) {
                        val value = j.optString("value")
                        pool.execute { runCatching { Base64.decode(value.substringAfter(','), Base64.DEFAULT) }.getOrNull()?.let { capture(it) } }
                    }
                }
            }
        } else diagnostics.add("CAPTURE_PDF", "origin-scoped blob bridge unavailable; download or import available")
        web.setDownloadListener { url, _, _, _, _ ->
            // The browser explicitly delivered a download, rather than a guessed report URL.
            handler.post { if (captureAllowed()) obtain(url) }
        }
        diagnostics.currentUrl = { web.url }
        EcrewBrowsers.current = this
        web.webViewClient = object : EcrewDiagnostics.Client(diagnostics) {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                super.shouldOverrideUrlLoading(view, request)
                val url = request.url.toString()
                val blocked = !allowed() || (!trusted(url) && !url.startsWith("blob:"))
                if (blocked) diagnostics.add("OVERRIDE", "blocked by NORMAL origin policy", url)
                return blocked
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                linkedInDocument = false
                if (trusted(url)) lifetime.sawEcrewPage()
                if ((!paused || loggingOut) && login(url)) loginShown()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!allowed() || (paused && !loggingOut) || clearingStorage) return
                if (login(url)) { loginShown(ready = true); return }
                if (!trusted(url)) return
                if (!loggingOut) { if (active) drive() else detectLinked() }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame && active) finish(false, "navigation failed")
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, reload: Boolean) {
                super.doUpdateVisitedHistory(view, url, reload)
                if (!allowed() || paused || clearingStorage) return
                if (login(url)) loginShown()
                else if (trusted(url) && !active && !linkedInDocument && !loggingOut) detectLinked()
            }
        }
        web.webChromeClient = object : EcrewDiagnostics.Chrome(diagnostics) {
            override fun onReceivedTitle(view: WebView, title: String?) {
                // Titles may contain account names: don't retain them in a shareable diagnostic log.
                if (allowed() && !paused && !clearingStorage && !loggingOut && trusted(view.url) && !login(view.url)) {
                    if (!active && !linkedInDocument) detectLinked()
                }
            }
        }
    }
    @android.annotation.SuppressLint("RestrictedApi")
    private fun configureRequestedWithHeader() {
        // AndroidX 1.12 restricts this legacy feature flag. Older providers still support the
        // requested opt-out; modern providers may omit the header already. Never call unguarded.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
    }
    override fun open() { if (allowed() && lifetime.open()) web.loadUrl(DASHBOARD) }
    override fun reload() { if (allowed() && !loggingOut && !clearingStorage) web.reload() }
    fun start() {
        if (!allowed() || active || loggingOut || clearingStorage || paused || terminated || login(web.url)) return
        handler.removeCallbacks(firstFetch)
        if (!background) RosterStore.prefs(c).edit().putBoolean("loopPaused", false).apply()
        active = true; manual = false; captured = false; nextMonth = false; attemptedDownloads.clear()
        handler.postDelayed(timeout, 90_000)
        if (!background) {
            eval("if(window.__mirrorRoster)window.__mirrorRoster.opened=false;")
            setStep("OPEN_MY_SCHEDULE"); drive()
        } else { setStep("LOAD_DASHBOARD"); open() }
    }
    private fun setStep(value: String) { step = value; since = SystemClock.elapsedRealtime(); diagnostics.add(step, "started") }
    private fun eval(script: String, done: (String) -> Unit = {}) {
        if (!allowed() || paused || login(web.url) || !trusted(web.url)) return
        web.evaluateJavascript(script) { if (allowed() && !paused && !login(web.url) && trusted(web.url)) done(runCatching { org.json.JSONTokener(it).nextValue()?.toString().orEmpty() }.getOrDefault("")) }
    }
    private fun linked() {
        if (paused || loggingOut || clearingStorage || terminated) return
        val firstInDocument = !linkedInDocument
        linkedInDocument = true
        val p = RosterStore.prefs(c); val fresh = !p.getBoolean("linked", false) || p.getBoolean("expired", false)
        if (firstInDocument) diagnostics.add("SESSION", "$instanceId $owner eCrew cookie count after login: ${EcrewStorage.cookieCount()}")
        p.edit().putBoolean("linked", true).putBoolean("expired", false).apply(); CookieManager.getInstance().flush()
        if (!background) {
            p.edit().putLong("lastInteractive", System.currentTimeMillis()).apply()
            if (fresh) Toast.makeText(c, "eCrew linked", Toast.LENGTH_SHORT).show()
            if (!active && !autoStarted && (fresh || p.getLong("lastSuccess", 0) == 0L)) {
                autoStarted = true; handler.postDelayed(firstFetch, 3000)
            }
        }
        // Periodic work always starts after its full interval; the activity owns this session meanwhile.
        RosterWork.configure(c)
        if (fresh) com.mirror.app.phone.DepartureAlerts.configure(c)
    }
    private fun detectLinked() {
        eval(script("DETECT", false)) { if (it == "terminated") sessionTerminated() else if (it == "linked") linked() }
    }
    /** Installs a Print click observer once. Network hooks are installed only when capture starts. */
    internal fun script(mode: String, capture: Boolean): String = """(function(){
      if(location.origin!=='$ORIGIN'||location.pathname.toLowerCase().includes('/login'))return 'login';
      const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden';
      const text=e=>(e.innerText||e.textContent||'').trim().toLowerCase();
      const find=t=>Array.from(document.querySelectorAll('button,a,span,div,label,input[type=button]')).find(e=>visible(e)&&(e.tagName==='INPUT'?e.value:text(e)).trim().toLowerCase()===t.toLowerCase());
      ${EcrewPageMatcher.javascript()}
      const schedule=()=>Array.from(document.querySelectorAll('a,button,span,div,label')).find(e=>visible(e)&&scheduleText(text(e)));
      const calendar=()=>Array.from(document.querySelectorAll('nav a,aside a,[role=navigation] a,.sidebar a,.sidebar-menu a,.nav a')).find(e=>visible(e)&&(/calendar/i.test(e.className+' '+(e.getAttribute('aria-label')||''))||Array.from(e.querySelectorAll('[class],[aria-label]')).some(i=>/calendar/i.test(i.className+' '+(i.getAttribute('aria-label')||'')))));
      const signedIn=()=>!!schedule()||!!calendar()||(Array.from(document.querySelectorAll('.avatar,.user-avatar,.user-initials,[class*=avatar],[class*=initials]')).some(visible)&&Array.from(document.querySelectorAll('header,.navbar,.topbar,.top-bar,[class*=topbar]')).some(visible));
      const terminated=()=>Array.from(document.querySelectorAll('div,span,p,section,[role=alert]')).some(e=>visible(e)&&terminatedText(text(e)));
      const click=t=>{if(t.toLowerCase()==='confirm all changes')return false;const e=find(t);if(!e)return false;(e.closest('button,a,[role=button]')||e).click();return true;};
      if(!window.__mirrorRoster){
        const s={active:false,capture:false,opened:false,terminatedSent:false};window.__mirrorRoster=s;
        const safe=()=>s.active&&s.capture&&location.origin==='$ORIGIN'&&!location.pathname.toLowerCase().includes('/login');
        const send=j=>{if(window.MirrorPdf)MirrorPdf.postMessage(JSON.stringify(j));};
        const deliver=b=>{if(!safe()||b.size>$LIMIT)return;let f=new FileReader();f.onload=()=>{if(safe())send({kind:'pdf',value:f.result});};f.readAsDataURL(b);};
        s.install=()=>{if(s.active)return;s.active=true;
          s.fetch=window.fetch;s.xhr=XMLHttpRequest.prototype.send;s.open=window.open;
          s.nativeFetch=window.fetch.bind(window);
          window.fetch=(...args)=>s.nativeFetch(...args).then(r=>{if(safe()&&(r.headers.get('content-type')||'').toLowerCase().includes('application/pdf'))r.clone().blob().then(deliver).catch(()=>{});return r;});
          XMLHttpRequest.prototype.send=function(...args){this.addEventListener('load',()=>{try{if(safe()&&(this.getResponseHeader('content-type')||'').toLowerCase().includes('application/pdf')){if(this.response instanceof Blob)deliver(this.response);else if(this.response instanceof ArrayBuffer)deliver(new Blob([this.response],{type:'application/pdf'}));}}catch(e){}});return s.xhr.apply(this,args);};
          s.obtain=u=>{if(!safe())return;try{const url=new URL(u,location.href);if(url.protocol==='blob:'||url.origin==='$ORIGIN')s.nativeFetch(url.href).then(r=>r.blob()).then(deliver).catch(()=>{});}catch(e){}};
          window.open=function(u,...args){if(safe()&&u){s.obtain(String(u));return null;}return s.open.call(this,u,...args);};
        };
        s.stop=()=>{if(s.active){window.fetch=s.fetch;XMLHttpRequest.prototype.send=s.xhr;window.open=s.open;}s.active=false;s.capture=false;};
        const watch=()=>{if(location.origin!=='$ORIGIN'||location.pathname.toLowerCase().includes('/login'))return;if(terminated()&&!s.terminatedSent){s.terminatedSent=true;send({kind:'terminated'});}};
        s.observer=new MutationObserver(()=>{if(s.watchTimer)clearTimeout(s.watchTimer);s.watchTimer=setTimeout(watch,100);});
        s.observer.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden']});watch();
        document.addEventListener('click',e=>{const target=e.target.closest('button,a,[role=button],input[type=button]');if(target&&visible(target)&&(target.tagName==='INPUT'?target.value:text(target)).trim().toLowerCase()==='print'){
          if(e.isTrusted&&!s.disabled){s.install();s.capture=true;send({kind:'print'});}
        }},true);
      }
      const s=window.__mirrorRoster,mode='$mode';
      if(mode==='STOP'){s.stop();return 'stopped';}
      if(terminated())return 'terminated';
      if(mode==='DETECT')return signedIn()?'linked':'wait';
      s.install();s.capture=$capture;
      if(mode==='LOAD_DASHBOARD')return signedIn()?'linked':'wait';
      if(mode==='OPEN_MY_SCHEDULE'){if((find('Period')||find('Period:'))&&schedule())return 'schedule';const e=schedule()||calendar();if(!s.opened&&e){(e.closest('button,a,[role=button]')||e).click();s.opened=true;}return 'wait';}
      if(mode==='CHECK_PENDING_CHANGES')return find('Confirm all changes')?'pending':'clear';
      if(mode==='CLICK_PRINT')return click('Print')?'printed':'wait';
      if(mode==='NEXT_PERIOD')return click('Next Period')?'next':'wait';
      if(mode==='EXIT'){click('Exit');return 'exit';}
      const overlays=Array.from(document.querySelectorAll('[role=dialog],.dx-popup-content,.dx-overlay-content,[class*=dxrd]')).filter(visible);
      const sources=Array.from(document.querySelectorAll('iframe,embed,object')).filter(e=>visible(e)&&(!!find('Exit')||overlays.some(parent=>parent.contains(e)))).map(e=>e.src||e.data||'').filter(Boolean);
      if(s.capture)sources.filter(u=>u.startsWith('blob:')).forEach(u=>s.obtain(u));
      return JSON.stringify({sources:sources,pending:!!find('Confirm all changes')});
    })()""".trimIndent()
    private fun drive() {
        if (!active || !allowed() || login(web.url)) return
        if (SystemClock.elapsedRealtime() - since > 25_000) { finish(false, "step timeout; manual Print or import available"); return }
        eval(script(step, captureAllowed())) { value ->
            if (!active) return@eval
            if (value == "login") { expire(); return@eval }
            if (value == "terminated") { sessionTerminated(); return@eval }
            when (step) {
                "LOAD_DASHBOARD" -> if (value == "linked") { linked(); setStep("OPEN_MY_SCHEDULE") }
                "OPEN_MY_SCHEDULE" -> if (value == "schedule") setStep("CHECK_PENDING_CHANGES")
                "CHECK_PENDING_CHANGES" -> { val pending = value == "pending"; RosterStore.prefs(c).edit().putBoolean("pendingChanges", pending).apply(); RosterChanges.update(c, pending = pending); if (pending) RosterNotices.pending(c); setStep("CLICK_PRINT") }
                "CLICK_PRINT" -> if (value == "printed") setStep("CAPTURE_PDF")
                "NEXT_PERIOD" -> if (value == "next") setStep("CLICK_PRINT")
                "CAPTURE_PDF" -> runCatching {
                    val j = JSONObject(value); val sources = j.getJSONArray("sources")
                    for (i in 0 until sources.length()) { val u = sources.getString(i); if (!u.startsWith("blob:")) obtain(u) }
                    if (j.optBoolean("pending")) RosterNotices.pending(c)
                }
            }
            if (active && allowed()) { handler.removeCallbacks(poll); handler.postDelayed(poll, 1500) }
        }
    }
    /** Allowed only for DownloadListener or a print overlay source during an active capture. */
    private fun obtain(url: String) {
        if (!captureAllowed() || login(web.url) || !trusted(web.url)) return
        if (url.startsWith("blob:")) { eval("if(window.__mirrorRoster&&window.__mirrorRoster.obtain)window.__mirrorRoster.obtain(${JSONObject.quote(url)});"); return }
        if (!trusted(url) || url in attemptedDownloads || !downloading.compareAndSet(false, true)) return
        attemptedDownloads += url
        val ua = web.settings.userAgentString; val referer = web.url.orEmpty()
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        diagnostics.add("CAPTURE_PDF", "browser download candidate", url)
        pool.execute {
            try {
                var current = url
                for (hop in 0..4) {
                    if (!captureAllowed() || !trusted(current)) break
                    val request = URL(current).openConnection() as HttpURLConnection; connection = request
                    request.connectTimeout = 10_000; request.readTimeout = 15_000; request.instanceFollowRedirects = false
                    request.setRequestProperty("Cookie", cookies); request.setRequestProperty("User-Agent", ua); request.setRequestProperty("Referer", referer)
                    try {
                        val status = request.responseCode; diagnostics.add("CAPTURE_PDF", "HTTP $status", current)
                        if (status in 300..399) { current = URL(URL(current), request.getHeaderField("Location") ?: break).toString(); if (login(current)) { handler.post { expire() }; break }; continue }
                        if (status == 200) request.inputStream.use { capture(it.readBytesLimited(LIMIT)) }
                        break
                    } finally { request.disconnect(); connection = null }
                }
            } catch (_: Exception) { if (!dead) diagnostics.add("CAPTURE_PDF", "download failed", url) }
            finally { downloading.set(false) }
        }
    }
    private fun capture(bytes: ByteArray) {
        if (!captureAllowed() || bytes.size < 4 || !bytes.copyOfRange(0,4).contentEquals("%PDF".toByteArray()) || bytes.contentHashCode() == lastPdfHash) return
        lastPdfHash = bytes.contentHashCode(); diagnostics.add("CAPTURE_PDF", "PDF captured")
        val success = RosterStore.accept(c, bytes, interactive = !background)
        handler.post {
            if (!allowed() || !active) return@post
            captured = success || captured
            if (success && !background) RosterStore.prefs(c).edit().putLong("lastInteractive", System.currentTimeMillis()).apply()
            eval(script("EXIT", false)) {
                val roster = RosterStore.load(c); val today = java.time.LocalDate.now(AirportZones.zone("MNL"))
                if (!manual && success && !nextMonth && roster != null && today >= roster.period.end.minusDays(4) && today <= roster.period.end) {
                    nextMonth = true; attemptedDownloads.clear(); setStep("NEXT_PERIOD"); handler.postDelayed(poll, 1500)
                } else finish(success, if (success) "success" else "parse failed")
            }
        }
    }
    private fun expire() {
        val p = RosterStore.prefs(c); val notice = active && p.getBoolean("linked", false) && !p.getBoolean("expired", false) && !loggingOut
        if (p.getBoolean("linked", false)) p.edit().putBoolean("expired", true).apply()
        RosterWork.cancel(c)
        if (notice) RosterNotices.post(c, 602, "eCrew session expired — tap to log in", "Open Roster Link")
        if (active) finish(false, "session expired")
    }
    private fun finish(success: Boolean, result: String) {
        val wasActive = active; active = false; connection?.disconnect(); connection = null
        handler.removeCallbacks(timeout); handler.removeCallbacks(poll)
        if (allowed() && !login(web.url)) eval(script("STOP", false))
        diagnostics.add(step, result)
        if (wasActive) completed(success || captured)
    }
    private fun sessionTerminated() {
        if (terminated || loggingOut || clearingStorage || paused) return
        terminated = true; handler.removeCallbacks(firstFetch)
        diagnostics.add("SESSION", "$instanceId $owner SESSION_TERMINATED by eCrew")
        RosterStore.prefs(c).edit().putBoolean("expired", true).apply(); RosterWork.cancel(c)
        finish(false, "eCrew terminated session")
        Toast.makeText(c, "eCrew ended this session (another session detected). Log out of the eCrew app/Chrome, then log in again.", Toast.LENGTH_LONG).show()
    }
    /** The only caller is the screen's explicit logout tap. Ineligible taps are discarded. */
    fun logoutFromTap() {
        logoutGate.tap(allowed() && !loggingOut && !clearingStorage && trusted(web.url) && !login(web.url)) {
            loggingOut = true; handler.removeCallbacks(firstFetch)
            finish(false, "explicit logout tap; waiting for Login"); RosterWork.cancel(c)
            logoutOrder.begin(SystemClock.elapsedRealtime()); handler.postDelayed(logoutTimeout, 10_000)
            web.evaluateJavascript("""(function(){if(location.origin!=='$ORIGIN'||location.pathname.toLowerCase().includes('/login'))return 'blocked';const e=Array.from(document.querySelectorAll('button,a,[role=button]')).find(e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&(/^(log out|logout|sign out)$/i.test((e.innerText||e.textContent||'').trim())||/^(log out|logout|sign out)$/i.test((e.getAttribute('title')||e.getAttribute('aria-label')||'').trim())||e.querySelector('.fa-power-off,.glyphicon-off,[class*=power-off]')));if(e)e.click();return e?'clicked':'missing';})()""") { result ->
                diagnostics.add("LOGOUT", if (result == "\"clicked\"") "eCrew logout control clicked; no deferred retry" else "eCrew logout control unavailable; no deferred retry")
            }
        }
    }
    private fun clearAfterLogout() {
        handler.removeCallbacks(logoutTimeout); clearingStorage = true
        diagnostics.add("LOGOUT", "Login reached after explicit tap; local cleanup only")
        RosterStore.prefs(c).edit().putBoolean("linked", false).putBoolean("expired", false).apply()
        com.mirror.app.phone.DepartureAlerts.configure(c)
        EcrewStorage.clearLocal(c) {
            clearingStorage = false; loggingOut = false; terminated = false
            freshStorageCleared = true; autoStarted = false; linkedInDocument = false
            logoutOrder.complete()
            diagnostics.add("SESSION", "eCrew cookie count after logout: ${EcrewStorage.cookieCount()}")
        }
    }
    private fun pauseForLoop() { RosterStore.prefs(c).edit().putBoolean("loopPaused", true).apply(); pauseAutomation("eCrew is reloading by itself — automation paused") }
    override fun pauseForLocalClear() { pauseAutomation(null) }
    private fun pauseAutomation(toast: String?) {
        if (paused) return
        // Restore capture hooks in the existing document only; never click or navigate.
        if (allowed() && !login(web.url)) eval("if(window.__mirrorRoster){const s=window.__mirrorRoster;s.disabled=true;s.stop();if(s.observer)s.observer.disconnect();if(s.watchTimer)clearTimeout(s.watchTimer);}")
        paused = true; val wasActive = active; active = false; loggingOut = false
        handler.removeCallbacksAndMessages(null); connection?.disconnect(); connection = null
        RosterWork.cancel(c)
        diagnostics.add("AUTOMATION", "paused for this WebView; no auto-retry")
        if (toast != null) Toast.makeText(c, toast, Toast.LENGTH_LONG).show()
        if (wasActive && background) completed(false)
    }
    override fun destroy() {
        if (dead) return
        if (allowed() && !paused && !login(web.url)) eval(script("STOP", false))
        if (EcrewBrowsers.current === this) EcrewBrowsers.current = null
        dead = true; active = false; lifetime.destroy(); handler.removeCallbacksAndMessages(null); connection?.disconnect(); connection = null; pool.shutdownNow()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "MirrorPdf")
        web.stopLoading(); web.destroy()
    }
}
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
    while (true) { val n = read(buf); if (n < 0) break; require(out.size() + n <= limit); out.write(buf, 0, n) }
    return out.toByteArray()
}
