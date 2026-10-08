package com.mirror.app.phone.roster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.concurrent.Executors

/** Lazily reached from the phone roster path only. Gecko may be initialized once per process. */
object EcrewFirefox {
    private var shared: GeckoRuntime? = null
    private var extension: GeckoResult<WebExtension>? = null
    private val foregroundHosts = mutableListOf<java.lang.ref.WeakReference<View>>()
    fun foregroundOpened(c: Context, anchor: View) {
        if (!RosterStore.phone(c)) return
        foregroundHosts.removeAll { it.get() == null || it.get() === anchor }
        foregroundHosts.add(java.lang.ref.WeakReference(anchor))
    }
    fun foregroundClosed(anchor: View) {
        foregroundHosts.removeAll { it.get() == null || it.get() === anchor }
        if (foregroundHost() == null) foregroundBrowser?.destroy()
    }
    private var foregroundBrowser: EcrewEngineBrowser? = null
    private fun foregroundHost() = foregroundHosts.asReversed().mapNotNull { it.get() }
        .firstOrNull { it.isAttachedToWindow }?.rootView as? android.view.ViewGroup
    fun canRefresh(c: Context) = !RosterStore.prefs(c).getBoolean("geckoForegroundOnly", false) || foregroundHost() != null
    fun limitBackground(c: Context) {
        RosterStore.prefs(c).edit().putBoolean("geckoForegroundOnly", true).apply()
        CaptureLog.add(c, "ENGINE", "background refresh limited: foreground only")
    }
    private fun runtime(c: Context): GeckoRuntime {
        check(Looper.myLooper() == Looper.getMainLooper())
        shared?.let { return it }
        val profile = File(c.noBackupFilesDir, "ecrew-gecko-profile").apply { mkdirs() }
        // Read before startup, including release builds. No external config, telemetry or account services.
        val config = File(c.noBackupFilesDir, "ecrew-gecko.yaml")
        config.writeText(c.assets.open("ecrew/runtime.yaml").bufferedReader().use { it.readText() })
        val settings = GeckoRuntimeSettings.Builder()
            .arguments(arrayOf("-profile", profile.absolutePath))
            .configFilePath(config.absolutePath)
            .remoteDebuggingEnabled(false).consoleOutput(false).loginAutofillEnabled(false)
            .crashHandler(null).build()
        return GeckoRuntime.create(c.applicationContext, settings).also { runtime ->
            shared = runtime
            runtime.notifyTelemetryPrefChanged(false)
            extension = runtime.webExtensionController.ensureBuiltIn("resource://android/assets/ecrew/", "ecrew@mirror.local")
        }
    }
    fun create(c: Context, lease: EcrewSessionCoordinator.Lease, background: Boolean = false,
        completed: (Boolean) -> Unit = {}): EcrewEngineBrowser {
        check(RosterStore.phone(c) && lease.ownsSession())
        val runtime = runtime(c)
        val host = if (background && RosterStore.prefs(c).getBoolean("geckoForegroundOnly", false))
            foregroundHost() ?: error("background refresh limited: foreground only") else null
        val browser = GeckoRosterBrowser(c, runtime, extension!!, lease, background, host) { success ->
            foregroundBrowser = null; completed(success)
        }
        if (host != null) foregroundBrowser = browser
        return browser
    }
    fun clearLocal(c: Context, done: () -> Unit) {
        // Local clear must not create a runtime, session, browser or network request.
        val existing = shared
        if (existing == null) {
            File(c.noBackupFilesDir, "ecrew-gecko-profile").deleteRecursively(); done(); return
        }
        existing.storageController.clearDataFromHost("ecrew.cebupacificair.com", StorageController.ClearFlags.SITE_DATA or StorageController.ClearFlags.AUTH_SESSIONS)
            .accept({ CaptureLog.add(c, "STORAGE", "local Firefox eCrew data cleared; no navigation"); done() }, {
                CaptureLog.add(c, "STORAGE", "Firefox local clear failed"); done()
            })
    }
}

/** Adapter kept separate so JVM tests can pass a synthetic WebResponse with an ordinary stream. */
object GeckoPdfCapture {
    fun read(response: WebResponse): ByteArray? = EcrewPdfBytes.read(response.body)
}
private class GeckoRosterBrowser(private val c: Context, private val runtime: GeckoRuntime,
    extension: GeckoResult<WebExtension>, private val lease: EcrewSessionCoordinator.Lease,
    private val background: Boolean, private val hiddenHost: android.view.ViewGroup?, private val completed: (Boolean) -> Unit) : EcrewEngineBrowser {
    override val instanceId = java.util.UUID.randomUUID().toString().take(8)
    override val mode = "FIREFOX"
    private val owner = if (background) "WORKER" else "INTERACTIVE"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val session = GeckoSession(GeckoSessionSettings.Builder()
        .usePrivateMode(false).userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE).build())
    private val geckoView = if (background && hiddenHost == null) null else GeckoView(hiddenHost?.context ?: c).also {
        if (hiddenHost != null) {
            it.alpha = 0f; it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            hiddenHost.addView(it, android.view.ViewGroup.LayoutParams(1, 1))
        }
    }
    override val view: View? get() = geckoView
    @Volatile private var dead = false
    @Volatile private var paused = false
    @Volatile private var url: String? = null
    @Volatile private var body: InputStream? = null
    private var ready = false
    private var opened = false
    private var wantOpen = false
    private var wantStart = false
    private var canBack = false
    private var autoStarted = false
    private var terminated = false
    private var loggingOut = false
    private var linkedDocument = false
    private var port: WebExtension.Port? = null
    private var transfer: String? = null
    private var chunks = StringBuilder()
    private var chunkIndex = 0
    private var chunkCount = 0
    private var chunkRequest = 0
    private var receivedResult = false
    private var probePending = false
    private var lastPdfHash = 0
    private val logoutOrder = EcrewLogoutOrder()
    private val loop = EcrewLoopDetector()
    private fun allowed() = !dead && !paused && lease.ownsSession() && RosterStore.phone(c)
    private fun page() = allowed() && EcrewPortPolicy.page(url) && !terminated && !loggingOut
    private fun log(step: String, result: String, address: String? = null) = CaptureLog.add(c, step, "$instanceId FIREFOX $owner $result", address)
    private val machine = EcrewAutomation(SystemClock::elapsedRealtime, { command, id ->
        if (page()) port?.postMessage(JSONObject().put("command", command).put("request", id))
    }, { value -> pending(value) }, { success, reason ->
        if (background && hiddenHost == null && reason.startsWith("timeout") && (!receivedResult || !linkedDocument) && !RosterFetcher.login(url) && !terminated)
            EcrewFirefox.limitBackground(c)
        stopContent(); log("FETCH", reason); if (background) main.post { completed(success) } else completed(success)
    }, { step -> log(step, "started") })
    private val tick = object : Runnable {
        override fun run() { if (allowed()) { machine.tick(); main.postDelayed(this, 500) } }
    }
    private val autoFetch = Runnable { if (page() && !machine.active) start() }
    private val logoutTimeout = Runnable {
        if (logoutOrder.timeout(SystemClock.elapsedRealtime())) {
            loggingOut = false; log("LOGOUT", "Login not reached within 10 s; storage retained")
            if (!background) Toast.makeText(c, "eCrew logout did not reach Login. Use eCrew’s logout and try again.", Toast.LENGTH_LONG).show()
        }
    }
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val trusted = RosterFetcher.trusted(request.uri) || (request.uri.startsWith("blob:https://ecrew.cebupacificair.com/") && machine.captureAllowed)
                val ok = allowed() && trusted
                log("NAVIGATION", if (ok) "load allowed" else "load blocked", request.uri)
                // Printing may request a window; reuse this leased session instead of opening a second one.
                if (ok && request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (machine.captureAllowed) session.loadUri(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onLocationChange(s: GeckoSession, location: String?, permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (RosterFetcher.login(location)) { disconnectPort(); linkedDocument = false }
                url = location; log("NAVIGATION", "location", location)
                val u = runCatching { android.net.Uri.parse(location) }.getOrNull()
                if (loop.navigation(u?.host, u?.path.orEmpty(), SystemClock.elapsedRealtime())) {
                    RosterStore.prefs(c).edit().putBoolean("loopPaused", true).apply()
                    log("NAVIGATION", "LOOP DETECTED; automation paused"); pauseForLocalClear(); return
                }
                if (RosterFetcher.login(location)) loginShown(false)
            }
            override fun onCanGoBack(s: GeckoSession, value: Boolean) { canBack = value }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                log("NETWORK_ERROR", "navigation failed", uri); machine.stop(reason = "navigation failed"); return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, location: String) {
                disconnectPort(); linkedDocument = false; url = location
                log("NAVIGATION", "start", location)
                if (RosterFetcher.login(location)) loginShown(false)
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                log("NAVIGATION", if (success) "finish" else "failed", url)
                if (RosterFetcher.login(url)) loginShown(true)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                if (!page() || !machine.captureAllowed || !(RosterFetcher.trusted(response.uri) || response.uri.startsWith("blob:https://ecrew.cebupacificair.com/"))) {
                    response.body?.close(); return
                }
                val id = machine.request
                response.setReadTimeoutMillis(15_000)
                body = response.body
                pool.execute {
                    val bytes = runCatching { GeckoPdfCapture.read(response) }.getOrNull()
                    body = null
                    main.post { if (bytes != null && page() && machine.captureAllowed && (id == machine.request || (machine.step == EcrewAutomation.Step.CAPTURE_PDF && id == machine.request - 1))) capture(bytes) else log("CAPTURE_PDF", "response ignored or unavailable") }
                }
            }
            override fun onCrash(s: GeckoSession) { log("ENGINE", "content process crashed; no upload or retry"); machine.stop(reason = "Firefox process ended") }
        }
        extension.accept({ installed ->
            if (!allowed() || installed == null) return@accept
            session.webExtensionController.setMessageDelegate(installed, object : WebExtension.MessageDelegate {
                override fun onConnect(candidate: WebExtension.Port) {
                    val sender = candidate.sender
                    if (!allowed() || sender.session !== session || !sender.isTopLevel || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT || !EcrewPortPolicy.page(sender.url)) {
                        candidate.disconnect(); return
                    }
                    disconnectPort(); url = sender.url; port = candidate
                    candidate.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (source !== port || !page()) return
                            (message as? JSONObject)?.let { receive(it) }
                        }
                        override fun onDisconnect(source: WebExtension.Port) { if (source === port) { port = null; resetChunks() } }
                    })
                    log("EXTENSION", "trusted content port connected")
                }
            }, "mirrorRoster")
            session.open(runtime); session.setActive(true); geckoView?.setSession(session)
            ready = true; main.post(tick)
            if (wantOpen) open()
            if (wantStart) begin()
        }, { log("EXTENSION", "built-in installation failed; import available"); main.post { if (!dead) completed(false) } })
        EcrewBrowsers.current = this
    }
    private fun receive(j: JSONObject) {
        when (j.optString("kind")) {
            "linked" -> linked()
            "terminated" -> {
                terminated = true; main.removeCallbacks(autoFetch); log("SESSION", "SESSION_TERMINATED by eCrew")
                RosterStore.prefs(c).edit().putBoolean("expired", true).apply(); RosterWork.cancel(c)
                machine.stop(reason = "eCrew terminated session")
                if (!background) Toast.makeText(c, "eCrew ended this session. Close other eCrew sessions, then log in again.", Toast.LENGTH_LONG).show()
            }
            "pending" -> pending(j.optBoolean("value"))
            "result" -> {
                receivedResult = true
                machine.response(j.optInt("request", -1), j.optString("result"), j.optBoolean("pending"))
            }
            "manualPrint" -> if (!background) { main.removeCallbacks(autoFetch); autoStarted = true; lastPdfHash = 0; machine.manualPrint() }
            "pdf" -> receiveChunk(j)
            "captureError" -> log("CAPTURE_PDF", "in-page capture unavailable; external response or import available")
            "probe" -> j.optJSONObject("value")?.let { value ->
                if (probePending && EcrewProbePolicy.dashboard(url)) {
                    probePending = false
                    log("PROBE", EcrewProbe.redact(value).toString())
                }
            }
        }
    }
    private fun linked() {
        if (!page()) return
        val p = RosterStore.prefs(c); val fresh = !p.getBoolean("linked", false) || p.getBoolean("expired", false)
        p.edit().putBoolean("linked", true).putBoolean("expired", false).apply()
        if (!linkedDocument) { linkedDocument = true; log("SESSION", "linked") }
        if (!background) {
            p.edit().putLong("lastInteractive", System.currentTimeMillis()).apply()
            if (fresh) { Toast.makeText(c, "eCrew linked", Toast.LENGTH_SHORT).show(); com.mirror.app.phone.DepartureAlerts.configure(c) }
            if (!autoStarted && !machine.active && !p.getBoolean("loopPaused", false)) { autoStarted = true; main.postDelayed(autoFetch, 3000) }
        }
        RosterWork.configure(c)
    }
    private fun pending(value: Boolean) {
        val p = RosterStore.prefs(c); val previous = p.getBoolean("pendingChanges", false)
        p.edit().putBoolean("pendingChanges", value).apply(); RosterChanges.update(c, pending = value)
        if (value && !previous) RosterNotices.pending(c)
    }
    override fun open() {
        if (!allowed()) return
        wantOpen = true
        if (ready && !opened) { opened = true; session.loadUri(RosterFetcher.DASHBOARD) }
    }
    override fun start() {
        if (!allowed() || terminated || loggingOut || RosterFetcher.login(url)) return
        main.removeCallbacks(autoFetch); autoStarted = true
        if (!background) RosterStore.prefs(c).edit().putBoolean("loopPaused", false).apply()
        if (!machine.active) { lastPdfHash = 0; resetChunks() }
        wantStart = true
        if (ready) begin()
    }
    private fun begin() { if (allowed() && !machine.active) { machine.start(); if (background) open() } }
    override fun reload() { if (allowed() && ready && !loggingOut) { machine.stop(reason = "explicit reload"); session.reload() } }
    override fun back(): Boolean { if (!allowed() || !canBack || !ready) return false; session.goBack(); return true }
    override fun probeFromTap() {
        if (!EcrewProbePolicy.allowed(true, url) || !page()) { Toast.makeText(c, "Run probe on an eCrew Dashboard page", Toast.LENGTH_LONG).show(); return }
        if (probePending || port == null) return
        probePending = true
        main.postDelayed({ if (probePending) { probePending = false; log("PROBE", "result timeout") } }, 15000)
        port?.postMessage(JSONObject().put("command", "probe").put("request", machine.request))
    }
    override fun logoutFromTap() {
        if (!page() || port == null || background) return
        main.removeCallbacks(autoFetch); autoStarted = true
        machine.stop(reason = "explicit logout tap; waiting for Login")
        loggingOut = true; RosterWork.cancel(c); logoutOrder.begin(SystemClock.elapsedRealtime())
        port?.postMessage(JSONObject().put("command", "logout").put("request", machine.request))
        main.postDelayed(logoutTimeout, 10_000)
    }
    private fun loginShown(loaded: Boolean) {
        if (loggingOut) {
            if (loaded && logoutOrder.loginShown(true, SystemClock.elapsedRealtime())) {
                main.removeCallbacks(logoutTimeout); paused = true; disconnectPort()
                // Close the session before clearing so Login cannot recreate persistent data.
                closeSession()
                EcrewFirefox.clearLocal(c) {
                    RosterStore.prefs(c).edit().putBoolean("linked", false).putBoolean("expired", false).apply()
                    logoutOrder.complete(); loggingOut = false; log("LOGOUT", "Login reached; local cleanup complete; reopen screen to sign in")
                    Toast.makeText(c, "Logged out of eCrew. Close and reopen eCrew to sign in.", Toast.LENGTH_LONG).show()
                    com.mirror.app.phone.DepartureAlerts.configure(c)
                }
            }
            return
        }
        val p = RosterStore.prefs(c)
        if (p.getBoolean("linked", false)) {
            p.edit().putBoolean("expired", true).apply(); RosterWork.cancel(c)
            if (machine.active) RosterNotices.post(c, 602, "eCrew session expired — tap to log in", "Open Roster Link")
        }
        machine.stop(reason = "session expired")
        main.removeCallbacks(autoFetch)
    }
    private fun receiveChunk(j: JSONObject) {
        if (!machine.captureAllowed) return
        val id = j.optInt("request", -1)
        // A PDF can arrive immediately from Print, before its acknowledgement advances the step.
        if (id != machine.request && !(machine.step == EcrewAutomation.Step.CAPTURE_PDF && id == machine.request - 1)) return
        val index = j.optInt("index", -1); val count = j.optInt("count", 0)
        val name = j.optString("transfer"); val value = j.optString("value")
        if (name.length !in 1..100 || count !in 1..214 || value.length !in 1..131072 || !value.matches(Regex("[A-Za-z0-9+/=]+"))) { resetChunks(); return }
        if (index == 0) { resetChunks(); transfer = name; chunkCount = count; chunkRequest = id }
        if (transfer != name || index != chunkIndex || count != chunkCount || id != chunkRequest || chunks.length + value.length > ((EcrewPdfBytes.LIMIT + 2) / 3) * 4) { resetChunks(); return }
        chunks.append(value); chunkIndex++
        if (chunkIndex == chunkCount) {
            val encoded = chunks.toString(); resetChunks()
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return
            capture(bytes)
        }
    }
    private fun capture(bytes: ByteArray) {
        if (!page() || !EcrewPdfBytes.valid(bytes) || bytes.contentHashCode() == lastPdfHash || !machine.pdf()) return
        lastPdfHash = bytes.contentHashCode(); resetChunks(); log("CAPTURE_PDF", "PDF captured")
        pool.execute {
            val success = allowed() && RosterStore.accept(c, bytes, interactive = !background)
            val roster = RosterStore.load(c); val today = LocalDate.now(AirportZones.zone("MNL"))
            val next = success && roster != null && today >= roster.period.end.minusDays(4) && today <= roster.period.end
            main.post { if (page()) machine.parsed(success, next) }
        }
    }
    private fun resetChunks() { transfer = null; chunks = StringBuilder(); chunkIndex = 0; chunkCount = 0 }
    private fun disconnectPort() { probePending = false; port?.setDelegate(null); port?.disconnect(); port = null; resetChunks() }
    private fun stopContent() { if (allowed() && EcrewPortPolicy.page(url)) port?.postMessage(JSONObject().put("command", "stop").put("request", machine.request)) }
    private fun closeSession() { body?.close(); body = null; geckoView?.releaseSession(); hiddenHost?.removeView(geckoView); if (session.isOpen) { session.setActive(false); session.close() }; ready = false }
    override fun pauseForLocalClear() {
        if (dead || paused) return
        stopContent(); paused = true; machine.stop(reason = "automation paused; no retry")
        main.removeCallbacksAndMessages(null); disconnectPort(); closeSession()
    }
    override fun destroy() {
        if (dead) return
        stopContent(); dead = true; machine.stop(reason = "session closed")
        main.removeCallbacksAndMessages(null); disconnectPort(); closeSession(); pool.shutdownNow()
        if (EcrewBrowsers.current === this) EcrewBrowsers.current = null
        log("LIFECYCLE", "destroy; profile retained")
    }
}
