package com.mirror.app.phone.roster

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.webkit.*

/** Passive only: callbacks never read bodies/headers, intercept responses or make a request. */
class EcrewDiagnostics(private val c: Context, val instanceId: String, val mode: String,
    private val owner: String, private val loop: () -> Unit = {}) {
    private val requests = EcrewRequestLogWindow()
    private val detector = EcrewLoopDetector()
    fun add(step: String, result: String, url: String? = null) =
        CaptureLog.add(c, step, "$instanceId $mode $owner $result", url)
    private fun observeNavigation(url: String?) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (detector.navigation(uri?.host, uri?.path.orEmpty(), SystemClock.elapsedRealtime())) {
            add("NAVIGATION", "LOOP DETECTED", url); loop()
        }
    }
    open class Client(private val d: EcrewDiagnostics) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            d.add("OVERRIDE", "redirect=${request.isRedirect} gesture=${request.hasGesture()} main=${request.isForMainFrame}", request.url.toString())
            if (request.isForMainFrame) d.observeNavigation(request.url.toString())
            return false
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val address = EcrewLogRedaction.address(request.url.toString())
            if (d.requests.record(request.method, address, SystemClock.elapsedRealtime()))
                d.add("REQUEST", "${request.method} main=${request.isForMainFrame}", request.url.toString())
            return null
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            d.add("NAVIGATION", "start${if (EcrewLogRedaction.isLogin(url)) "; login page shown" else ""}; eCrew cookies=${EcrewStorage.cookieCount()}", url)
            d.observeNavigation(url)
        }
        override fun onPageFinished(view: WebView, url: String?) {
            d.add("NAVIGATION", "finish; eCrew cookies=${EcrewStorage.cookieCount()}", url)
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, reload: Boolean) {
            d.add("NAVIGATION", "history reload=$reload", url)
            d.observeNavigation(url)
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            d.add("HTTP_ERROR", "status=${response.statusCode} ${request.method} main=${request.isForMainFrame}", request.url.toString())
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            d.add("NETWORK_ERROR", "code=${error.errorCode} description=${EcrewLogRedaction.error(error.description.toString())} main=${request.isForMainFrame}", request.url.toString())
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            d.add("SSL_ERROR", "code=${error.primaryError}; cancelled", error.url)
            handler.cancel()
        }
    }
    open class Chrome(private val d: EcrewDiagnostics) : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (EcrewProbe.console(message, d.currentUrl())) return true
            val login = EcrewLogRedaction.isLogin(message.sourceId()) || EcrewLogRedaction.isLogin(d.currentUrl())
            d.add("CONSOLE", "${message.messageLevel()} ${EcrewLogRedaction.console(message.message(), login)} line=${message.lineNumber()}", message.sourceId())
            return false
        }
    }
    // Assigned by the owning browser, so console callbacks can suppress Login messages too.
    var currentUrl: () -> String? = { null }
}
interface EcrewBrowser {
    val instanceId: String
    val mode: String
    fun open()
    fun reload()
    fun destroy()
    fun pauseForLocalClear() {}
}
class EcrewPlainBrowser(c: Context, private val web: WebView,
    private val lease: EcrewSessionCoordinator.Lease, private val lifetime: EcrewBrowserLifetime,
    private val browserMode: EcrewBrowserMode = EcrewBrowserMode.PLAIN) : EcrewBrowser {
    override val instanceId = java.util.UUID.randomUUID().toString().take(8)
    override val mode = browserMode.name
    private val diagnostics = EcrewDiagnostics(c, instanceId, mode, "INTERACTIVE")
    init {
        // Everything else, including UA, requested-with, cache and window support, stays default.
        @Suppress("DEPRECATION")
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true }
        if (browserMode == EcrewBrowserMode.CLEAN) EcrewCleanSettings.apply(c, web.settings) { diagnostics.add("BROWSER_SETTINGS", it) }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        EcrewBrowsers.current = this
        diagnostics.currentUrl = { web.url }
        web.webViewClient = EcrewDiagnostics.Client(diagnostics)
        web.webChromeClient = EcrewDiagnostics.Chrome(diagnostics)
    }
    override fun open() { if (lease.ownsSession() && lifetime.open()) web.loadUrl(RosterFetcher.DASHBOARD) }
    override fun reload() { if (lease.ownsSession()) web.reload() }
    override fun destroy() { EcrewProbe.cancel(web); if (EcrewBrowsers.current === this) EcrewBrowsers.current = null; web.stopLoading(); web.destroy() }
}

/** Local-data actions never create a browser, navigate, enqueue logout or contact eCrew. */
object EcrewBrowsers { @Volatile var current: EcrewBrowser? = null }
