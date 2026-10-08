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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One leased browser. Login fields and login documents are never inspected with JavaScript. */
class RosterFetcher(private val c: Context, val web: WebView,
    private val lease: EcrewSessionCoordinator.Lease, private val background: Boolean = false,
    private val completed: (Boolean) -> Unit = {}) {
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
    private var logoutCleanup: (() -> Unit)? = null
    private val timeout = Runnable { finish(false, "90 s timeout") }
    private val poll = Runnable { drive() }
    private val firstFetch = Runnable { if (allowed() && !active && !login(web.url)) start() }
    private fun allowed() = !dead && lease.ownsSession() && RosterStore.phone(c)
    private fun captureAllowed() = allowed() && active && step in listOf("CLICK_PRINT", "CAPTURE_PDF")
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            // Leave userAgentString unset: WebView's default and Android client hints agree.
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MirrorPdf", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (!allowed() || !mainFrame || !trusted(origin.toString()) || login(web.url) || !trusted(web.url)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > LIMIT * 4 / 3 + 256) return@addWebMessageListener
                val j = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
                when (j.optString("kind")) {
                    "print" -> if (!background) {
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
        } else CaptureLog.add(c, "CAPTURE_PDF", "origin-scoped blob bridge unavailable; download or import available")
        web.setDownloadListener { url, _, _, _, _ ->
            // The browser explicitly delivered a download, rather than a guessed report URL.
            handler.post { if (captureAllowed()) obtain(url) }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return !allowed() || (!trusted(url) && !url.startsWith("blob:"))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                linkedInDocument = false
                if (login(url)) {
                    CaptureLog.add(c, "NAVIGATION", "login page shown", url)
                    expire()
                } else CaptureLog.add(c, "NAVIGATION", "main frame started", url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!allowed()) return
                if (login(url)) return // No evaluation, title access or field access on login pages.
                if (!trusted(url)) return
                CaptureLog.add(c, "NAVIGATION", "main frame finished; HTTP status unavailable; title=${view.title.orEmpty().replace('\n', ' ').replace('\r', ' ').take(120)}", url)
                if (active) drive() else detectLinked()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) CaptureLog.add(c, "NAVIGATION", if (login(request.url.toString())) "login page shown; HTTP ${response.statusCode}" else "HTTP ${response.statusCode}", request.url.toString())
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { CaptureLog.add(c, "NAVIGATION", "main frame failed", request.url.toString()); if (active) finish(false, "navigation failed") }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, reload: Boolean) {
                if (!allowed()) return
                if (login(url)) { CaptureLog.add(c, "NAVIGATION", "login page shown", url); expire() }
                else if (trusted(url) && !active && !linkedInDocument) detectLinked()
            }
            // No shouldInterceptRequest override: browsing never duplicates authenticated requests.
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (allowed() && trusted(view.url) && !login(view.url)) {
                    CaptureLog.add(c, "NAVIGATION", "title=${title.orEmpty().replace('\n', ' ').replace('\r', ' ').take(120)}", view.url)
                    if (!active && !linkedInDocument) detectLinked()
                }
            }
        }
    }
    fun open() { if (allowed()) web.loadUrl(DASHBOARD) }
    fun reload() { if (allowed()) web.reload() }
    fun start() {
        if (!allowed() || active) return
        handler.removeCallbacks(firstFetch)
        active = true; manual = false; captured = false; nextMonth = false; attemptedDownloads.clear()
        handler.postDelayed(timeout, 90_000)
        if (!background && linkedInDocument && !login(web.url)) { eval("if(window.__mirrorRoster)window.__mirrorRoster.opened=false;"); setStep("OPEN_MY_SCHEDULE"); drive() }
        else { setStep("LOAD_DASHBOARD"); web.loadUrl(DASHBOARD) }
    }
    private fun setStep(value: String) { step = value; since = SystemClock.elapsedRealtime(); CaptureLog.add(c, step, "started") }
    private fun eval(script: String, done: (String) -> Unit = {}) {
        if (!allowed() || login(web.url) || !trusted(web.url)) return
        web.evaluateJavascript(script) { if (allowed() && !login(web.url) && trusted(web.url)) done(runCatching { org.json.JSONTokener(it).nextValue()?.toString().orEmpty() }.getOrDefault("")) }
    }
    private fun linked() {
        linkedInDocument = true
        val p = RosterStore.prefs(c); val fresh = !p.getBoolean("linked", false) || p.getBoolean("expired", false)
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
        eval(script("DETECT", false)) { if (it == "linked") linked() }
    }
    /** Installs a Print click observer once. Network hooks are installed only when capture starts. */
    internal fun script(mode: String, capture: Boolean): String = """(function(){
      if(location.origin!=='$ORIGIN'||location.pathname.toLowerCase().includes('/login'))return 'login';
      const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden';
      const text=e=>(e.innerText||e.textContent||'').trim().toLowerCase();
      const find=t=>Array.from(document.querySelectorAll('button,a,span,div,label,input[type=button]')).find(e=>visible(e)&&(e.tagName==='INPUT'?e.value:text(e)).trim().toLowerCase()===t.toLowerCase());
      const click=t=>{if(t.toLowerCase()==='confirm all changes')return false;const e=find(t);if(!e)return false;(e.closest('button,a,[role=button]')||e).click();return true;};
      if(!window.__mirrorRoster){
        const s={active:false,capture:false,opened:false};window.__mirrorRoster=s;
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
        document.addEventListener('click',e=>{const target=e.target.closest('button,a,[role=button],input[type=button]');if(target&&visible(target)&&(target.tagName==='INPUT'?target.value:text(target)).trim().toLowerCase()==='print'){
          if(e.isTrusted){s.install();s.capture=true;send({kind:'print'});}
        }},true);
      }
      const s=window.__mirrorRoster,mode='$mode';
      if(mode==='STOP'){s.stop();return 'stopped';}
      if(mode==='DETECT')return find('My Schedule')?'linked':'wait';
      s.install();s.capture=$capture;
      if(mode==='LOAD_DASHBOARD')return find('My Schedule')?'linked':'wait';
      if(mode==='OPEN_MY_SCHEDULE'){if(find('My Schedule')&&(find('Period')||find('Period:')))return 'schedule';if(!s.opened&&click('My Schedule'))s.opened=true;return 'wait';}
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
        val ua = WebSettings.getDefaultUserAgent(c); val referer = web.url.orEmpty()
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        CaptureLog.add(c, "CAPTURE_PDF", "browser download candidate", url)
        pool.execute {
            try {
                var current = url
                for (hop in 0..4) {
                    if (!captureAllowed() || !trusted(current)) break
                    val request = URL(current).openConnection() as HttpURLConnection; connection = request
                    request.connectTimeout = 10_000; request.readTimeout = 15_000; request.instanceFollowRedirects = false
                    request.setRequestProperty("Cookie", cookies); request.setRequestProperty("User-Agent", ua); request.setRequestProperty("Referer", referer)
                    try {
                        val status = request.responseCode; CaptureLog.add(c, "CAPTURE_PDF", "HTTP $status", current)
                        if (status in 300..399) { current = URL(URL(current), request.getHeaderField("Location") ?: break).toString(); if (login(current)) { handler.post { expire() }; break }; continue }
                        if (status == 200) request.inputStream.use { capture(it.readBytesLimited(LIMIT)) }
                        break
                    } finally { request.disconnect(); connection = null }
                }
            } catch (_: Exception) { if (!dead) CaptureLog.add(c, "CAPTURE_PDF", "download failed", url) }
            finally { downloading.set(false) }
        }
    }
    private fun capture(bytes: ByteArray) {
        if (!captureAllowed() || bytes.size < 4 || !bytes.copyOfRange(0,4).contentEquals("%PDF".toByteArray()) || bytes.contentHashCode() == lastPdfHash) return
        lastPdfHash = bytes.contentHashCode(); CaptureLog.add(c, "CAPTURE_PDF", "PDF captured")
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
        CaptureLog.add(c, step, result)
        if (wasActive) completed(success || captured)
    }
    fun logout() {
        if (!allowed()) return
        loggingOut = true; handler.removeCallbacks(firstFetch)
        finish(false, "manual logout"); RosterWork.cancel(c)
        RosterStore.prefs(c).edit().putBoolean("linked", false).putBoolean("expired", false).apply()
        com.mirror.app.phone.DepartureAlerts.configure(c)
        val clear = {
            // Cookie names are used only to expire this origin's cookies; names/values are never logged.
            val manager = CookieManager.getInstance()
            val names = listOf(ORIGIN, DASHBOARD, "$ORIGIN/eCrew/Login").joinToString(";") { manager.getCookie(it).orEmpty() }.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
            names.forEach { name -> listOf("/", "/eCrew", "/eCrew/", "/eCrew/Dashboard/").forEach { path ->
                manager.setCookie(ORIGIN, "$name=; Max-Age=0; Path=$path; Secure")
                manager.setCookie(ORIGIN, "$name=; Max-Age=0; Path=$path; Domain=ecrew.cebupacificair.com; Secure")
                manager.setCookie(ORIGIN, "$name=; Max-Age=0; Path=$path; Domain=.cebupacificair.com; Secure")
            } }; manager.flush(); if (allowed()) web.loadUrl("$ORIGIN/eCrew/Login")
        }
        logoutCleanup = clear
        if (!login(web.url) && trusted(web.url)) eval("""(function(){const e=Array.from(document.querySelectorAll('button,a,[role=button]')).find(e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden'&&(/^(log out|logout|sign out)$/i.test((e.innerText||e.textContent||'').trim())||/^(log out|logout|sign out)$/i.test((e.getAttribute('title')||e.getAttribute('aria-label')||'').trim())||e.querySelector('.fa-power-off,.glyphicon-off,[class*=power-off]')));if(e)e.click();return 'logout';})()""")
        handler.postDelayed({ clear() }, 1000)
    }
    fun destroy() {
        if (dead) return
        if (allowed() && !login(web.url)) eval(script("STOP", false))
        dead = true; active = false; handler.removeCallbacksAndMessages(null); if (loggingOut) logoutCleanup?.invoke(); logoutCleanup = null; connection?.disconnect(); connection = null; pool.shutdownNow()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "MirrorPdf")
        web.stopLoading(); web.destroy()
    }
}
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
    while (true) { val n = read(buf); if (n < 0) break; require(out.size() + n <= limit); out.write(buf, 0, n) }
    return out.toByteArray()
}
