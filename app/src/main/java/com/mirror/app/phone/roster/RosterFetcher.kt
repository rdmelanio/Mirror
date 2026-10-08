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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns all portal automation. No JS is evaluated on /Login; no login fields are inspected. */
class RosterFetcher(private val c: Context, val web: WebView, private val background: Boolean = false,
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
    private val fetching = AtomicBoolean(false)
    private var dead = false
    private var active = false
    private var step = "LOAD_DASHBOARD"
    private var stepSince = 0L
    private var nextMonth = false
    private var captured = false
    private var pending = false
    private var lastPdfHash = 0
    private val timeout = Runnable { finish(false, "90 s timeout") }
    private val poll = Runnable { drive() }
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // Unlike addJavascriptInterface (which exposes Java to EVERY iframe), this bridge is origin-scoped.
        // It is never injected/used on the login page and has no field/cookie APIs.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MirrorPdf", setOf(ORIGIN)) { _, message, origin, _, _ ->
                if (dead || !trusted(origin.toString()) || login(web.url) || !trusted(web.url)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > LIMIT * 4 / 3 + 64) { CaptureLog.add(c, "CAPTURE_PDF", "blob exceeds size limit"); return@addWebMessageListener }
                pool.execute {
                    runCatching { Base64.decode(data.substringAfter(','), Base64.DEFAULT) }.getOrNull()?.let { capture(it) }
                }
            }
        }
        web.setDownloadListener { url, ua, _, _, _ ->
            handler.post { if (!login(web.url) && trusted(web.url)) obtain(url, ua) }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !trusted(request.url.toString())
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (login(url)) expire() else if (trusted(url)) { stepSince = SystemClock.elapsedRealtime() }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (login(url)) { expire(); return }
                if (trusted(url)) { if (!active && RosterStore.prefs(c).getBoolean("expired", false)) setStep("LOAD_DASHBOARD"); handler.removeCallbacks(poll); drive() }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method == "GET" && trusted(url) && Regex("pdf|export|dxxrd|report|print", RegexOption.IGNORE_CASE).containsMatchIn(request.url.path.orEmpty())) {
                    handler.post { if (!login(web.url) && trusted(web.url)) obtain(url, web.settings.userAgentString) }
                }
                return null
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && active) finish(false, "navigation failed")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                val child = WebView(c)
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        obtain(request.url.toString(), web.settings.userAgentString); v.destroy(); return true
                    }
                }
                (message.obj as WebView.WebViewTransport).webView = child; message.sendToTarget()
                handler.postDelayed({ runCatching { child.destroy() } }, 10_000)
                return true
            }
        }
    }
    fun start() {
        if (dead || active || !RosterStore.phone(c)) return
        if (background && RosterStore.prefs(c).getBoolean("expired", false)) { completed(false); return }
        active = true; captured = false; pending = false; nextMonth = false
        setStep("LOAD_DASHBOARD"); handler.postDelayed(timeout, 90_000); web.loadUrl(DASHBOARD)
    }
    fun reload() { if (!dead) web.reload() }
    private fun setStep(value: String) {
        step = value; stepSince = SystemClock.elapsedRealtime(); CaptureLog.add(c, step, "started")
    }
    private fun eval(script: String, done: (String) -> Unit) {
        if (dead || login(web.url) || !trusted(web.url)) return
        web.evaluateJavascript(script) { if (!dead && !login(web.url) && trusted(web.url)) done(runCatching { org.json.JSONTokener(it).nextValue()?.toString() ?: "" }.getOrDefault("")) }
    }
    private fun drive() {
        if (dead || login(web.url) || !trusted(web.url)) return
        if (active && SystemClock.elapsedRealtime() - stepSince > 25_000) { finish(false, "step timeout; manual Print or import available"); return }
        // Exact visible labels only; Confirm all changes is deliberately absent from click targets.
        val script = """(function(){
          if(location.origin !== '$ORIGIN' || location.pathname.toLowerCase().includes('/login')) return 'login';
          const visible=e=>!!(e.getClientRects().length)&&getComputedStyle(e).visibility!=='hidden';
          const text=e=>(e.innerText||e.textContent||'').trim().toLowerCase();
          const find=s=>Array.from(document.querySelectorAll('button,a,span,div,label,input[type=button]')).find(e=>visible(e)&&((e.tagName==='INPUT'?e.value:text(e)).trim().toLowerCase()===s.toLowerCase()));
          const schedule=!!find('My Schedule');
          const period=!!find('Period') || !!find('Period:');
          const pending=!!find('Confirm all changes');
          function click(s){if(s==='Confirm all changes')return false;let e=find(s);if(!e)return false;(e.closest('button,a,[role=button]')||e).click();return true;}
          const scan=()=>{document.querySelectorAll('iframe,embed,object').forEach(e=>{const u=e.src||e.data;if(u)window.mirrorCapture(u);});};
          if(!window.mirrorCapture){window.mirrorCapture=function(u){
            if(location.origin !== '$ORIGIN' || location.pathname.toLowerCase().includes('/login'))return;
            if(u.startsWith('blob:'))fetch(u).then(r=>r.blob()).then(b=>{if(b.size>$LIMIT)return;let f=new FileReader();f.onload=()=>{if(window.MirrorPdf)MirrorPdf.postMessage(f.result);};f.readAsDataURL(b);}).catch(()=>{});
          }; const open=window.open;window.open=function(u,...a){if(u){window.mirrorCapture(String(u));}return open.call(this,u,...a);};}
          const sources=Array.from(document.querySelectorAll('iframe,embed,object')).map(e=>e.src||e.data||'').filter(Boolean);
          sources.forEach(u=>window.mirrorCapture(u));
          const mode='$step';
          if(mode==='LOAD_DASHBOARD')return schedule?'linked':'wait';
          if(mode==='OPEN_MY_SCHEDULE'){if(schedule&&period)return 'schedule';click('My Schedule');return 'wait';}
          if(mode==='CHECK_PENDING_CHANGES')return pending?'pending':'clear';
          if(mode==='CLICK_PRINT')return click('Print')?'printed':'wait';
          if(mode==='NEXT_PERIOD')return click('Next Period')?'next':'wait';
          if(mode==='EXIT'){click('Exit');return 'exit';}
          return JSON.stringify({sources:sources,pending:pending});
        })()""".trimIndent()
        eval(script) { value ->
            if (value == "login") { expire(); return@eval }
            when (step) {
                "LOAD_DASHBOARD" -> if (value == "linked") {
                    val p = RosterStore.prefs(c); val first = !p.getBoolean("linked", false)
                    p.edit().putBoolean("linked", true).putBoolean("expired", false).apply(); CookieManager.getInstance().flush()
                    if (first && !background) Toast.makeText(c, "eCrew linked", Toast.LENGTH_SHORT).show()
                    RosterWork.configure(c); if (active) setStep("OPEN_MY_SCHEDULE") else setStep("CAPTURE_PDF")
                }
                "OPEN_MY_SCHEDULE" -> if (value == "schedule") setStep("CHECK_PENDING_CHANGES")
                "CHECK_PENDING_CHANGES" -> { pending = value == "pending"; RosterStore.prefs(c).edit().putBoolean("pendingChanges", pending).apply(); if (pending) RosterNotices.pending(c); setStep("CLICK_PRINT") }
                "CLICK_PRINT" -> if (value == "printed") setStep("CAPTURE_PDF")
                "NEXT_PERIOD" -> if (value == "next") setStep("CLICK_PRINT")
                "CAPTURE_PDF" -> {
                    // evaluateJavascript returns a JSON-encoded string; decode both layers safely.
                    runCatching { val j = org.json.JSONObject(value)
                        val a = j.getJSONArray("sources"); for (i in 0 until a.length()) { val u = a.getString(i); if (!u.startsWith("blob:")) obtain(u, web.settings.userAgentString) }
                        if (j.optBoolean("pending")) RosterNotices.pending(c)
                    }
                }
            }
            if (!dead && (active || !background)) { handler.removeCallbacks(poll); handler.postDelayed(poll, 1500) }
        }
    }
    private fun obtain(url: String, ua: String) {
        if (dead || login(web.url) || !trusted(web.url)) return
        if (url.startsWith("blob:")) {
            eval("if(window.mirrorCapture)window.mirrorCapture(${org.json.JSONObject.quote(url)});'ok'") {}; return
        }
        if (!trusted(url) || !fetching.compareAndSet(false, true)) return
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        CaptureLog.add(c, "CAPTURE_PDF", "candidate", url)
        pool.execute {
            try {
                var current = url
                for (hop in 0..4) {
                    if (dead || !trusted(current)) break
                    val connection = URL(current).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cookie", cookies); connection.setRequestProperty("User-Agent", ua)
                    try {
                        val status = connection.responseCode
                        if (status in 300..399) {
                            current = URL(URL(current), connection.getHeaderField("Location") ?: break).toString()
                            if (login(current)) { handler.post { expire() }; break }; continue
                        }
                        if (status != 200) break
                        val bytes = connection.inputStream.use { it.readBytesLimited(LIMIT) }
                        if (isPdf(bytes)) capture(bytes)
                        break
                    } finally { connection.disconnect() }
                }
            } catch (_: Exception) { CaptureLog.add(c, "CAPTURE_PDF", "candidate failed", url) }
            finally { fetching.set(false) }
        }
    }
    private fun isPdf(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 37.toByte() && bytes[1] == 80.toByte() && bytes[2] == 68.toByte() && bytes[3] == 70.toByte()
    private fun capture(bytes: ByteArray) {
        if (dead || !isPdf(bytes) || bytes.contentHashCode() == lastPdfHash) return
        lastPdfHash = bytes.contentHashCode(); CaptureLog.add(c, "CAPTURE_PDF", "PDF captured")
        val success = RosterStore.accept(c, bytes)
        handler.post {
            if (dead) return@post
            captured = success
            eval("(function(){if(location.origin!=='$ORIGIN'||location.pathname.toLowerCase().includes('/login'))return;const e=Array.from(document.querySelectorAll('button,a,span')).find(e=>e.getClientRects().length&&(e.innerText||'').trim().toLowerCase()==='exit');if(e)(e.closest('button,a')||e).click();})()") {
                if (!active) return@eval
                val roster = RosterStore.load(c)
                val today = java.time.LocalDate.now(AirportZones.zone("MNL"))
                if (success && !nextMonth && roster != null && today >= roster.period.end.minusDays(4) && today <= roster.period.end) {
                    nextMonth = true; setStep("NEXT_PERIOD"); handler.postDelayed(poll, 1500)
                } else finish(success, if (success) "success" else "parse failed")
            }
        }
    }
    private fun expire() {
        val p = RosterStore.prefs(c); val wasExpired = p.getBoolean("expired", false)
        p.edit().putBoolean("expired", true).apply(); RosterWork.cancel(c)
        CaptureLog.add(c, step, "session expired")
        if (!wasExpired && p.getBoolean("linked", false)) RosterNotices.post(c, 602, "eCrew session expired — tap to log in", "Open Roster Link")
        if (active) finish(false, "session expired")
    }
    private fun finish(success: Boolean, result: String) {
        val wasActive = active; active = false; handler.removeCallbacks(timeout); handler.removeCallbacks(poll)
        CaptureLog.add(c, step, result); step = "CAPTURE_PDF"
        if (wasActive) completed(success || captured)
        if (!dead && !background && !login(web.url)) handler.postDelayed(poll, 1500)
    }
    fun destroy() {
        dead = true; active = false; handler.removeCallbacksAndMessages(null); pool.shutdownNow()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "MirrorPdf")
        web.stopLoading(); web.destroy()
    }
}
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
    while (true) { val n = read(buf); if (n < 0) break; require(out.size() + n <= limit); out.write(buf, 0, n) }
    return out.toByteArray()
}
