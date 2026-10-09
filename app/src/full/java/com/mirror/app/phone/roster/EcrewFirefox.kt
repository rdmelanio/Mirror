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
    private var autoFetchPending = false
    private val contentTabs = mutableMapOf<WebExtension.Port, Int>()
    private val childTabs = linkedSetOf<Int>()
    private var terminated = false
    private var loggingOut = false
    private var linkedDocument = false
    private val ports = linkedSetOf<WebExtension.Port>()
    private val frames = EcrewFrames<WebExtension.Port>()
    private var chunkPort: WebExtension.Port? = null
    private var fetchPending = false
    private var pendingPdf: ByteArray? = null
    private var child: GeckoSession? = null
    private val childPorts = linkedSetOf<WebExtension.Port>()
    private val childTrusted = linkedSetOf<WebExtension.Port>()
    private var networkPort: WebExtension.Port? = null
    private val networkTabs = linkedSetOf<Int>()
    private var foreground = true
    private var installedExtension: WebExtension? = null
    private val childTimeout = Runnable { closeChild() }
    private var snapshotUntil = 0L
    private var transfer: String? = null
    private var chunks = StringBuilder()
    private var chunkIndex = 0
    private var chunkCount = 0
    private var chunkRequest = 0
    private var receivedResult = false
    private var probePending = false
    private var lastPdfHash = 0
    private var viewerJsonSeen = false
    private var scheduleDataCaptured = false
    private var fetchCompletion: (() -> Unit)? = null
    private val exportWindow = EcrewExportWindow(SystemClock::elapsedRealtime) { log("EXPORT_WINDOW", it) }
    private fun snapshotStorage() {
        frames.top()?.postMessage(JSONObject().put("command", "storageSnapshot").put("request", machine.request))
    }
    private val logoutOrder = EcrewLogoutOrder()
    private val loop = EcrewLoopDetector()
    private fun allowed() = !dead && !paused && lease.ownsSession() && RosterStore.phone(c)
    private fun page() = allowed() && EcrewPortPolicy.page(url) && !terminated && !loggingOut
    private fun log(step: String, result: String, address: String? = null) = CaptureLog.add(c, step, "$instanceId FIREFOX $owner $result", address)
    private val machine: EcrewAutomation = EcrewAutomation(SystemClock::elapsedRealtime, { command, id ->
        if (page()) {
            if (command == "print") { viewerJsonSeen = false; exportWindow.arm("robot CLICK_PRINT"); networkScope() }
            broadcast(command, id)
        }
    }, { value -> pending(value) }, { success, reason ->
        if (background && hiddenHost == null && reason.startsWith("timeout") && (!receivedResult || !linkedDocument) && !RosterFetcher.login(url) && !terminated)
            EcrewFirefox.limitBackground(c)
        if (success && reason == "success" && !terminated) {
            val value = fetchPending || frames.pending
            RosterStore.prefs(c).edit().putBoolean("pendingChanges", value).apply()
            RosterChanges.update(c, pending = value)
        }
        snapshotStorage(); networkScope(); stopContent(); if (!exportWindow.armed) closeChild(); pendingPdf = null; log("FETCH", if (success && reason != "success") "data captured; $reason" else reason)
        fetchCompletion = { completed(success || scheduleDataCaptured) }
        // The final read-only snapshot must reach private storage before a worker closes its session.
        main.postDelayed({ finishFetch() }, 1500)
    }, { step -> networkScope(); log(step, "started"); if (step == "PARSE") main.post { parsePendingPdf() }
        if (step == "CAPTURE_PDF" && pendingPdf != null) main.post { pendingPdf?.let { capture(it, "webRequest") } } }, { step ->
        log("SNAPSHOT", "$step timeout; requesting frame diagnostics")
        snapshotUntil = SystemClock.elapsedRealtime() + 1000
        broadcast("snapshot", machine.request)
    })
    private val tick = object : Runnable {
        override fun run() { if (allowed()) { machine.tick(); networkScope(); if (!exportWindow.armed && child != null) closeChild(); main.postDelayed(this, 500) } }
    }
    private val autoFetch = Runnable { if (foreground && page() && !machine.active) { autoFetchPending = false; start() } }
    private fun finishFetch() { val completion = fetchCompletion; fetchCompletion = null; completion?.invoke() }
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
                val popup = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                val trusted = RosterFetcher.trusted(request.uri) || (exportWindow.armed && EcrewPortPolicy.exportPage(request.uri, allowBlank = popup))
                val ok = allowed() && trusted
                log("NAVIGATION", if (ok) "load allowed" else "load blocked", request.uri)
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW && !exportWindow.armed) return GeckoResult.fromValue(AllowOrDeny.DENY)
                return GeckoResult.fromValue(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onSubframeLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                log("FRAME_LOAD", "path=${EcrewSnapshot.path(runCatching { java.net.URI(request.uri).path }.getOrNull().orEmpty())}")
                return null
            }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!page() || !exportWindow.armed || !exportAddress(uri)) return null
                closeChild()
                val download = GeckoSession(GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE).build())
                child = download
                download.navigationDelegate = object : GeckoSession.NavigationDelegate {
                    override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                        GeckoResult.fromValue(if (page() && exportWindow.armed && exportAddress(request.uri)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                    override fun onLocationChange(s: GeckoSession, location: String?, permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                        if (location != null && location != "about:blank" && exportAddress(location)) log("CAPTURE_PDF", "export popup location", location)
                    }
                }
                download.contentDelegate = object : GeckoSession.ContentDelegate {
                    override fun onExternalResponse(s: GeckoSession, response: WebResponse) { externalPdf(response, "popup-external-response") }
                }
                installedExtension?.let { installed -> download.webExtensionController.setMessageDelegate(installed, object : WebExtension.MessageDelegate {
                    override fun onConnect(candidate: WebExtension.Port) {
                        val sender = candidate.sender
                        if (child !== download || !page() || !exportWindow.armed || sender.session !== download || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT || !exportAddress(sender.url) || childPorts.size >= 8) { log("PORT_REJECT", "export child session/environment/origin/Login/limit", sender.url); candidate.disconnect(); return }
                        childPorts.add(candidate)
                        candidate.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                if (source !in childPorts || !page()) return
                                val j = message as? JSONObject ?: return
                                if (j.optString("kind") == "hello") {
                                    if (!EcrewPortPolicy.page("https://ecrew.cebupacificair.com${j.optString("path")}")) { log("HELLO_REJECT", "export child path policy", source.sender.url); childPorts.remove(source); source.disconnect(); return }
                                    childTrusted.add(source)
                                    source.postMessage(JSONObject().put("command", "state").put("request", machine.request).put("active", true).put("capture", true))
                                }
                                if (source in childTrusted && j.optString("kind") == "hello" && j.optInt("tab", -1) >= 0) { networkTabs.add(j.optInt("tab")); childTabs.add(j.optInt("tab")); networkScope() }
                            }
                            override fun onDisconnect(source: WebExtension.Port) { childPorts.remove(source); childTrusted.remove(source); if (chunkPort === source) resetChunks() }
                        })
                    }
                }, "mirrorRoster") }
                main.post { if (child === download && download.isOpen) download.setActive(true) }
                main.postDelayed(childTimeout, 180_000)
                log("CAPTURE_PDF", "hidden export popup opened; export window 180 s")
                // Gecko opens the returned session on the same runtime. It must be unopened here.
                return GeckoResult.fromValue(download)
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
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) { externalPdf(response, "external-response") }
            override fun onCrash(s: GeckoSession) { log("ENGINE", "content process crashed; no upload or retry"); machine.stop(reason = "Firefox process ended") }
        }
        extension.accept({ installed ->
            if (!allowed() || installed == null) return@accept
            installedExtension = installed
            installed.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(candidate: WebExtension.Port) {
                    val sender = candidate.sender
                    if (!allowed() || sender.webExtension.id != installed.id || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION || sender.session != null || !sender.url.startsWith("moz-extension://") || !sender.url.substringAfterLast('/').startsWith("_generated_background_page.html")) {
                        log("PORT_REJECT", "network sender rejected", sender.url); candidate.disconnect(); return
                    }
                    networkPort?.disconnect(); networkPort = candidate
                    candidate.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (source !== networkPort || !allowed()) return
                            (message as? JSONObject)?.let { receiveNetwork(source, it) }
                        }
                        override fun onDisconnect(source: WebExtension.Port) { if (source === networkPort) networkPort = null }
                    })
                    networkScope()
                }
            }, "mirrorRosterNet")
            session.webExtensionController.setMessageDelegate(installed, object : WebExtension.MessageDelegate {
                override fun onConnect(candidate: WebExtension.Port) {
                    val sender = candidate.sender
                    if (allowed() && sender.webExtension.id == installed.id && sender.session === session && sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT && sender.isTopLevel && EcrewPortPolicy.page(sender.url) && ports.size >= 8) {
                        ports.firstOrNull { !it.sender.isTopLevel }?.let { old ->
                            ports.remove(old); frames.disconnect(old); contentTabs.remove(old)
                            old.disconnect(); log("PORT_REJECT", "subframe displaced for top driver", old.sender.url)
                        }
                    }
                    if (!allowed() || sender.webExtension.id != installed.id || sender.session !== session || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT || (!EcrewPortPolicy.page(sender.url) && !(sender.url in listOf("about:blank", "about:srcdoc") && EcrewPortPolicy.page(url))) || ports.size >= 8) {
                        log("PORT_REJECT", "content session/environment/origin/Login/limit", sender.url); candidate.disconnect(); return
                    }
                    ports.add(candidate)
                    candidate.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                            if (source !in ports || !page()) return
                            (message as? JSONObject)?.let { receive(source, it) }
                        }
                        override fun onDisconnect(source: WebExtension.Port) { ports.remove(source); frames.disconnect(source); contentTabs.remove(source)?.let { tab -> if (tab !in contentTabs.values && tab !in childTabs) networkTabs.remove(tab) }; networkScope(); if (chunkPort === source) resetChunks() }
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
    private fun networkScope() {
        networkPort?.postMessage(JSONObject().put("kind", "scope").put("id", instanceId)
            .put("tabs", org.json.JSONArray(networkTabs.toList())).put("request", machine.request)
            .put("active", page() && machine.active).put("capture", page() && exportWindow.armed)
            .put("record", page() && frames.frames.values.any { it.recording }))
    }
    private fun receiveNetwork(source: WebExtension.Port, j: JSONObject) {
        val kind = j.optString("kind")
        if (kind == "ready") { networkScope(); return }
        if (kind == "unavailable") { log("NETWORK_CAPTURE", "network capture unavailable"); return }
        if (j.optString("scope") != instanceId) return
        if (kind == "child") {
            if (child != null && page() && exportWindow.armed && j.optInt("opener", -1) in networkTabs && j.optInt("tab", -1) >= 0) {
                networkTabs.add(j.optInt("tab")); childTabs.add(j.optInt("tab")); networkScope()
            }
            return
        }
        val path = j.optString("path")
        if (!page() || j.optInt("tab", -1) !in networkTabs || !path.startsWith("/") || path.startsWith("//") || path.contains('?') || path.contains('#') || path.contains("login", true)) return
        when (kind) {
            "pdf" -> receiveChunk(source, j)
            "scheduleHtml" -> {
                val html = j.optString("body")
                if (path.equals("/eCrew/CrewSchedule", true) || path.equals("/eCrew/CrewSchedule/", true)) {
                    pool.execute { if (allowed()) EcrewScheduleData.saveHtml(c, html) }
                    main.postDelayed({ if (page()) snapshotStorage() }, 500)
                }
            }
            "scheduleData" -> if (path.startsWith("/eCrew/", true) && (machine.active || frames.frames.values.any { it.recording })) {
                val status = j.optInt("status"); val data = j.optString("body")
                if (data.length < EcrewScheduleData.LIMIT) pool.execute {
                    if (allowed()) EcrewScheduleData.decode(path, status, data)?.takeIf { allowed() }?.let { EcrewScheduleData.save(c, it) }
                }
            }
            "network" -> {
                val type = j.optString("contentType").take(120).replace(Regex("[^A-Za-z0-9 /;=.+_-]"), "")
                val copied = j.optString("copied").takeIf { it in setOf("pdf", "json", "html", "none") } ?: "none"
                if (path.equals("/AIMS/CrewScheduleReport/WebDocumentViewerInvoke", true) && type.contains("json", true) && j.optLong("size") > 0) viewerJsonSeen = true
                log("NETWORK_CAPTURE", "path=${EcrewSnapshot.path(path)} type=$type attachment=${j.optBoolean("attachment")} size=${j.optLong("size").coerceAtLeast(0)} copied=$copied")
            }
        }
    }
    override fun foregroundChanged(active: Boolean) {
        if (background || dead || foreground == active) return
        foreground = active
        if (!active) {
            machine.pause(); main.removeCallbacks(autoFetch)
            if (machine.active) log("FETCH", "fetch paused (app in background)")
            broadcast("pause", machine.request)
            if (session.isOpen) session.setActive(true)
        } else {
            machine.resume(); broadcast("resume", machine.request)
            if (machine.active) { log("FETCH", "fetch resumed"); if (machine.step == EcrewAutomation.Step.PARSE) parsePendingPdf() else machine.tick() }
            else if (linkedDocument && autoFetchPending) main.postDelayed(autoFetch, 3000)
        }
    }
    private fun receive(source: WebExtension.Port, j: JSONObject) {
        if (j.optString("kind") == "hello") {
            val top = source.sender.isTopLevel
            val senderPath = runCatching { java.net.URI(source.sender.url).path }.getOrNull()
            if (j.optBoolean("top") != top || (source.sender.url !in listOf("about:blank", "about:srcdoc") && senderPath != j.optString("path")) || !frames.hello(source, top, j.optString("path"))) {
                log("HELLO_REJECT", "top/path/policy mismatch", source.sender.url)
                ports.remove(source); frames.disconnect(source); source.disconnect()
            }
            if (source in frames.frames && j.optInt("tab", -1) >= 0) { contentTabs[source] = j.optInt("tab"); networkTabs.add(j.optInt("tab")); networkScope() }
            if (source in frames.frames) source.postMessage(JSONObject().put("command", "state").put("request", machine.request).put("active", machine.active).put("capture", machine.captureAllowed))
            return
        }
        val frame = frames.frames[source] ?: return
        when (j.optString("kind")) {
            "storageSnapshot" -> if (frame.top) {
                val entries = j.optJSONObject("entries") ?: return
                val afterSchedule = j.optBoolean("afterSchedule")
                pool.execute {
                    if (allowed()) {
                        val saved = EcrewScheduleData.saveStorage(c, entries, afterSchedule)
                        main.post {
                            if (saved && allowed()) { scheduleDataCaptured = true; machine.scheduleDataSaved(); log("FETCH_DATA", "data captured after CrewSchedule load") }
                            if (j.optString("reason") == "fetch end") finishFetch()
                        }
                    }
                }
            }
            "storageLimit" -> if (frame.top) log("LOCAL_STORAGE", "snapshot stopped: exceeds 5 MB")
            "scheduleLoaded" -> log("FRAME_LOAD", "CrewSchedule document loaded")
            "clickTarget" -> log("CLICK_TARGET", "tag=${EcrewSnapshot.text(j.optString("tag"))} classes=${j.optString("classes").take(160)} webix_tm_id=${EcrewSnapshot.text(j.optString("tm"))} webix_l_id=${EcrewSnapshot.text(j.optString("li"))} text=${EcrewSnapshot.text(j.optString("text"))}")
            "recording" -> { frame.recording = j.optBoolean("value"); networkScope() }
            "linked" -> { frame.linked = true; if (frames.linked) linked() }
            "waiting" -> { frame.linked = false }
            "terminated" -> {
                frame.terminated = true
                if (terminated || !frames.terminated) return
                exportWindow.clear(); terminated = true; main.removeCallbacks(autoFetch); log("SESSION", "SESSION_TERMINATED by eCrew")
                RosterStore.prefs(c).edit().putBoolean("expired", true).apply(); RosterWork.cancel(c)
                RosterNotices.post(c, 602, "eCrew session ended — tap to log in", "Open Roster Link")
                machine.stop(reason = "eCrew terminated session")
                if (!background) Toast.makeText(c, "eCrew ended this session. Close other eCrew sessions, then log in again.", Toast.LENGTH_LONG).show()
            }
            "pending" -> { frame.pending = j.optBoolean("value"); pending(frames.pending) }
            "result" -> {
                receivedResult = true
                frame.pending = j.optBoolean("pending"); pending(frames.pending)
                frames.result(source, j.optInt("request", -1), j.optInt("round", -1), j.optString("result"))?.let {
                    machine.response(j.optInt("request", -1), it, frames.pending)
                }
            }
            "manualExport" -> if (!background && j.optString("value") in setOf("PDF", "export")) { exportWindow.arm("trusted ${j.optString("value")} tap"); networkScope() }
            "manualPrint" -> if (!background) { exportWindow.arm("trusted Print tap"); viewerJsonSeen = false; networkScope(); main.removeCallbacks(autoFetch); autoFetchPending = false; autoStarted = true; lastPdfHash = 0; fetchPending = frames.pending; machine.manualPrint() }
            "deepSearch" -> log("DEEP_SEARCH", "${machine.step} result=${j.optString("result").takeIf { it in setOf("wait", "schedule", "pending", "clear", "printed", "preview", "export", "pdf", "exit", "next") } ?: "ignored"}")
            "snapshot" -> if (frame.top && SystemClock.elapsedRealtime() <= snapshotUntil) {
                val list = j.optJSONArray("frames") ?: return
                for (i in 0 until minOf(list.length(), 80)) {
                    val f = list.optJSONObject(i) ?: continue
                    val texts = f.optJSONArray("texts")
                    val labels = (0 until minOf(texts?.length() ?: 0, 15)).map { EcrewSnapshot.text(texts!!.optString(it)) }
                    log("SNAPSHOT", "depth=${f.optInt("depth").coerceIn(0, 80)} path=${EcrewSnapshot.path(f.optString("path"))} accessible=${f.optBoolean("accessible")} visible=${f.optBoolean("visible")} size=${f.optInt("width").coerceIn(0, 100000)}x${f.optInt("height").coerceIn(0, 100000)} clickable=$labels")
                }
            }
            "exportStep" -> {
                val value = j.optString("value")
                if (value in setOf("reveal tap", "export visible y", "export visible n", "menu shown", "PDF clicked", "export hidden last resort")) log("EXPORT", value)
            }
            "probe" -> j.optJSONObject("value")?.let { value ->
                if (frame.top && probePending && EcrewProbePolicy.dashboard(url)) {
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
        if (!linkedDocument) { linkedDocument = true; networkScope(); log("SESSION", "linked"); snapshotStorage() }
        if (!background) {
            p.edit().putLong("lastInteractive", System.currentTimeMillis()).apply()
            if (fresh) { Toast.makeText(c, "eCrew linked", Toast.LENGTH_SHORT).show(); com.mirror.app.phone.DepartureAlerts.configure(c) }
            if (!autoStarted && !machine.active && !p.getBoolean("loopPaused", false)) { autoStarted = true; autoFetchPending = true; if (foreground) main.postDelayed(autoFetch, 3000) }
        }
        RosterWork.configure(c)
    }
    private fun pending(value: Boolean) {
        if (!machine.active || !value) return
        fetchPending = true
        val p = RosterStore.prefs(c); val previous = p.getBoolean("pendingChanges", false)
        p.edit().putBoolean("pendingChanges", true).apply(); RosterChanges.update(c, pending = true)
        if (!previous) RosterNotices.pending(c, force = true)
    }
    override fun open() {
        if (!allowed()) return
        wantOpen = true
        if (ready && !opened) { opened = true; session.loadUri(RosterFetcher.DASHBOARD) }
    }
    override fun start() {
        if (!allowed() || terminated || loggingOut || RosterFetcher.login(url)) return
        main.removeCallbacks(autoFetch); autoFetchPending = false; autoStarted = true
        if (!background) RosterStore.prefs(c).edit().putBoolean("loopPaused", false).apply()
        if (!machine.active) { pendingPdf = null; lastPdfHash = 0; resetChunks() }
        wantStart = true
        if (ready) begin()
    }
    private fun begin() { if (allowed() && !machine.active) { fetchCompletion = null; scheduleDataCaptured = false; fetchPending = false; machine.start(); if (background) open() } }
    override fun reload() { if (allowed() && ready && !loggingOut) { machine.stop(reason = "explicit reload"); session.reload() } }
    override fun back(): Boolean { if (!allowed() || !canBack || !ready) return false; session.goBack(); return true }
    override fun probeFromTap() {
        if (!EcrewProbePolicy.allowed(true, url) || !page()) { Toast.makeText(c, "Run probe on an eCrew Dashboard page", Toast.LENGTH_LONG).show(); return }
        if (probePending || frames.top() == null) return
        probePending = true
        main.postDelayed({ if (probePending) { probePending = false; log("PROBE", "result timeout") } }, 15000)
        frames.top()?.postMessage(JSONObject().put("command", "probe").put("request", machine.request))
    }
    override fun logoutFromTap() {
        if (!page() || frames.top() == null || background) return
        main.removeCallbacks(autoFetch); autoFetchPending = false; autoStarted = true
        machine.stop(reason = "explicit logout tap; waiting for Login")
        loggingOut = true; RosterWork.cancel(c); logoutOrder.begin(SystemClock.elapsedRealtime())
        frames.top()?.postMessage(JSONObject().put("command", "logout").put("request", machine.request))
        main.postDelayed(logoutTimeout, 10_000)
    }
    private fun loginShown(loaded: Boolean) {
        exportWindow.clear()
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
    private fun receiveChunk(source: WebExtension.Port, j: JSONObject) {
        if (!exportWindow.armed) return
        val id = j.optInt("request", -1)
        // A PDF can arrive immediately from Print, before its acknowledgement advances the step.
        if (!exportWindow.armed) return
        val index = j.optInt("index", -1); val count = j.optInt("count", 0)
        val name = j.optString("transfer"); val value = j.optString("value")
        if (name.length !in 1..100 || count !in 1..214 || value.length !in 1..131072 || !value.matches(Regex("[A-Za-z0-9+/=]+"))) { resetChunks(); return }
        if (chunkPort != null && chunkPort !== source) return
        if (index == 0) { resetChunks(); chunkPort = source; transfer = name; chunkCount = count; chunkRequest = id }
        if (transfer != name || index != chunkIndex || count != chunkCount || id != chunkRequest || chunks.length + value.length > ((EcrewPdfBytes.LIMIT + 2) / 3) * 4) { resetChunks(); return }
        chunks.append(value); chunkIndex++
        if (chunkIndex == chunkCount) {
            val encoded = chunks.toString(); resetChunks()
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return
            capture(bytes, j.optString("via", "overlay").takeIf { it in setOf("webRequest") } ?: "webRequest")
        }
    }
    private fun exportAddress(address: String?) = EcrewPortPolicy.exportPage(address, allowBlank = true)
    private fun externalPdf(response: WebResponse, via: String) {
        if (!page() || !exportWindow.armed || !exportAddress(response.uri) || response.uri == "about:blank") { response.body?.close(); return }
        log("PDF_CANDIDATE", "path=${EcrewSnapshot.path(runCatching { java.net.URI(response.uri).path }.getOrNull().orEmpty())} type=${response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.take(120).orEmpty()} attachment=${response.headers.entries.any { it.key.equals("content-disposition", true) && it.value.contains("attachment", true) }} size=${response.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: -1} via=$via")
        response.setReadTimeoutMillis(60_000); body = response.body
        pool.execute {
            val bytes = runCatching { GeckoPdfCapture.read(response) }.getOrNull(); body = null
            main.post { if (bytes != null && page() && exportWindow.armed) capture(bytes, via) else log("CAPTURE_PDF", "response ignored or unavailable") }
        }
    }
    private fun closeChild() {
        networkTabs.removeAll(childTabs); childTabs.clear(); networkScope()
        main.removeCallbacks(childTimeout)
        val old = childPorts.toList(); childPorts.clear(); childTrusted.clear(); old.forEach { it.setDelegate(null); it.disconnect() }
        if (chunkPort in old) resetChunks()
        child?.let { if (it.isOpen) { it.setActive(false); it.close() } }; child = null
    }
    private fun capture(bytes: ByteArray, via: String) {
        if (!page() || !EcrewPdfBytes.valid(bytes) || bytes.contentHashCode() == lastPdfHash || !exportWindow.armed) return
        if (!machine.active) {
            lastPdfHash = bytes.contentHashCode()
            log("CAPTURE_PDF", "PDF captured via $via; manual delivery")
            pool.execute {
                val success = allowed() && RosterStore.accept(c, bytes, interactive = !background)
                main.post { if (allowed()) { log("IMPORT", "via=$via success=$success"); if (success && !background) Toast.makeText(c, "Roster imported", Toast.LENGTH_LONG).show() } }
            }
            return
        }
        pendingPdf = bytes
        // An early response may be buffered, but cannot bypass the two export steps.
        if (!machine.pdf()) return
        lastPdfHash = bytes.contentHashCode(); resetChunks(); closeChild(); log("CAPTURE_PDF", "PDF captured via $via; exiting viewer before parse")
    }
    private fun parsePendingPdf() {
        if (!background && !foreground) return
        val bytes = pendingPdf ?: return; pendingPdf = null
        if (!page() || machine.step != EcrewAutomation.Step.PARSE) return
        pool.execute {
            val success = allowed() && RosterStore.accept(c, bytes, interactive = !background)
            val roster = RosterStore.load(c); val today = LocalDate.now(AirportZones.zone("MNL"))
            val next = success && roster != null && today >= roster.period.end.minusDays(4) && today <= roster.period.end
            main.post { if (page()) { log("IMPORT", "success=$success"); if (success && !background) Toast.makeText(c, "Roster imported", Toast.LENGTH_LONG).show(); machine.parsed(success, next) } }
        }
    }
    private fun resetChunks() { chunkPort = null; transfer = null; chunks = StringBuilder(); chunkIndex = 0; chunkCount = 0 }
    private fun broadcast(command: String, id: Int) {
        val round = frames.begin(id)
        frames.frames.keys.toList().forEach { it.postMessage(JSONObject().put("command", command).put("request", id).put("round", round).put("viewerReady", viewerJsonSeen).put("drive", frames.top() == null || frames.top() === it)) }
    }
    private fun disconnectPort() {
        probePending = false; val old = ports.toList(); ports.clear(); frames.clear(); contentTabs.clear(); networkTabs.clear(); networkScope()
        old.forEach { it.setDelegate(null); it.disconnect() }; resetChunks()
    }
    private fun stopContent() { if (allowed() && EcrewPortPolicy.page(url)) broadcast("stop", machine.request) }
    private fun closeSession() { pendingPdf = null; closeChild(); body?.close(); body = null; geckoView?.releaseSession(); hiddenHost?.removeView(geckoView); if (session.isOpen) { session.setActive(false); session.close() }; ready = false }
    override fun pauseForLocalClear() {
        if (dead || paused) return
        exportWindow.clear(); stopContent(); paused = true; machine.stop(reason = "automation paused; no retry")
        main.removeCallbacksAndMessages(null); disconnectPort(); closeSession()
    }
    override fun destroy() {
        if (dead) return
        exportWindow.clear(); stopContent(); dead = true; machine.stop(reason = "session closed")
        main.removeCallbacksAndMessages(null); disconnectPort(); closeSession(); networkPort?.disconnect(); networkPort = null; installedExtension?.setMessageDelegate(null, "mirrorRosterNet"); pool.shutdownNow()
        if (EcrewBrowsers.current === this) EcrewBrowsers.current = null
        log("LIFECYCLE", "destroy; profile retained")
    }
}

