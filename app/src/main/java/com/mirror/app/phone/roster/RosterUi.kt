package com.mirror.app.phone.roster

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import com.mirror.app.core.*
import com.mirror.app.phone.PhoneTheme
import com.mirror.app.phone.PhoneUi
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

object RosterDisplay {
    fun next(c: Context, now: Instant = Instant.now()): Duty? {
        val duties = RosterStore.load(c)?.duties.orEmpty()
        val today = now.atZone(AirportZones.zone("MNL")).toLocalDate()
        duties.firstOrNull { it.date == today && it.code == "CHECK" }?.let { return it }
        val active = duties.filter { it.reportInstant != null && it.reportInstant <= now && it.releaseInstant != null && it.releaseInstant > now }.minByOrNull { it.reportInstant!! }
        if (active != null) return active
        val off = duties.firstOrNull { it.date == today && (it.type == DutyType.OFF || it.type == DutyType.LEAVE) }
        if (off != null) return off
        val upcoming = duties.filter { it.reportInstant != null && it.reportInstant > now }.minByOrNull { it.reportInstant!! }
        val check = duties.filter { it.code == "CHECK" && it.date >= today }.minByOrNull { it.date }
        return if (check != null && (upcoming == null || check.date <= upcoming.date)) check else upcoming
    }
    fun compact(c: Context, now: Instant = Instant.now()): String {
        val d = next(c, now) ?: return "Link eCrew or import a roster PDF"
        if (d.code == "CHECK") return "⚠ check eCrew"
        if (d.type == DutyType.OFF || d.type == DutyType.LEAVE) return if (d.type == DutyType.OFF) "OFF" else d.code
        val report = d.reportInstant ?: return d.code
        if (report <= now && d.releaseInstant != null && d.releaseInstant > now) return "ON DUTY · release ${d.releaseLocal?.toLocalTime()}${if (d.releaseEstimated) " (est)" else ""}"
        val minutes = maxOf(0, Duration.between(now, report).toMinutes())
        val leg = d.legs.firstOrNull()
        return "${leg?.let { "5J${it.flightNo} ${it.depApt}→${it.arrApt}" } ?: d.code} · RPT ${d.reportLocal?.toLocalTime()} · T-${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }
    fun legs(d: Duty) = d.legs.joinToString("\n") { "${if (it.deadhead) "DHC " else ""}5J${it.flightNo} ${it.depApt} ${if (it.depKind == "S") "" else it.depKind}${it.depTime.toLocalTime()} → ${it.arrApt} ${if (it.arrKind == "S") "" else it.arrKind}${it.arrTime.toLocalTime()} ${it.aircraft.orEmpty()}" }
}
class ECrewActivity : ComponentActivity() {
    private var firefox: EcrewEngineBrowser? = null
    private var browser: EcrewBrowser? = null
    private var browserMode = EcrewBrowserMode.CLEAN
    private var web: WebView? = null
    private var lease: EcrewSessionCoordinator.Lease? = null
    private var screen: EcrewSessionCoordinator.Screen? = null
    private val lifetime = EcrewBrowserLifetime()
    private lateinit var root: LinearLayout
    private fun lifecycle(event: String) = CaptureLog.add(this, "LIFECYCLE", "${browser?.instanceId.orEmpty()} ${browser?.mode ?: browserMode.name} INTERACTIVE ECrewActivity $event")
    override fun onCreate(state: Bundle?) {
        PhoneTheme.install(this); super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        RosterPrivacy.apply(this)
        val useFirefox = EcrewEngine.selected(this) == EcrewEngine.FIREFOX
        browserMode = EcrewBrowserMode.CLEAN
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this)
        root.addView(PhoneTheme.appBar(this, "eCrew") { back() })
        top.addView(action("Reload") { browser?.reload() }, LinearLayout.LayoutParams(-2, -2))
        if (useFirefox) {
            top.addView(action("Fetch roster now") { firefox?.start() }, LinearLayout.LayoutParams(-2, -2))
            top.addView(action("Log out of eCrew") { firefox?.logoutFromTap() }, LinearLayout.LayoutParams(-2, -2))
        } else {
            top.addView(action("Run probe") { web?.let { EcrewProbe.runFromTap(this, it, browser?.instanceId.orEmpty(), browserMode.name) } }, LinearLayout.LayoutParams(-2, -2))
            top.addView(action("Copy log") { copyRosterLog() }, LinearLayout.LayoutParams(-2, -2))
        }
        if (useFirefox) {
            top.addView(action("Run probe") { firefox?.probeFromTap() })
            top.addView(action("Copy log") { copyRosterLog() })
        }
        top.addView(action("Close") { finish() }, LinearLayout.LayoutParams(-2, -2))
        PhoneUi.style(top); root.addView(HorizontalScrollView(this).apply { addView(top) })
        setContentView(root); insetContent(root, 0)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        screen = EcrewSessionLock.coordinator.openScreen()
        val granted = EcrewSessionLock.coordinator.acquireInteractive()
        if (granted == null) { lifecycle("create denied: session owned"); Toast.makeText(this, "eCrew is already open", Toast.LENGTH_SHORT).show(); finish(); return }
        lease = granted
        if (lifetime.create()) {
            if (useFirefox) {
                firefox = EcrewFirefox.create(this, granted) { success ->
                    Toast.makeText(this, if (success) "Roster updated" else "Fetch failed — try Print manually or import a PDF", Toast.LENGTH_LONG).show()
                }
                browser = firefox
                firefox?.view?.let { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
            } else {
                val modeChanged = EcrewBrowserModes.opened(this, browserMode)
                val fallback = WebView(this); web = fallback
                root.addView(fallback, LinearLayout.LayoutParams(-1, 0, 1f))
                browser = EcrewPlainBrowser(this, fallback, granted, lifetime, EcrewBrowserMode.CLEAN)
                Toast.makeText(this, "Clean browser mode — use manual Print or import a PDF", Toast.LENGTH_LONG).show()
                if (modeChanged) Toast.makeText(this, "Tip: Capture log → Clear data, then log in fresh", Toast.LENGTH_LONG).show()
            }
            lifecycle("create")
            this.browser?.open()
        }
    }
    private fun back() { if (firefox?.back() == true) return; val browser = web; if (browser?.canGoBack() == true) browser.goBack() else finish() }
    override fun onStart() {
        super.onStart()
        firefox?.foregroundChanged(true); if (!::root.isInitialized) return
        lifetime.start(); lifecycle("start"); RosterPrivacy.apply(this)
        if (firefox == null) CaptureLog.add(this, "WEBVIEW", "${browser?.instanceId.orEmpty()} ${browserMode.name} ${EcrewWebViewInfo.text(this)}")
    }
    override fun onStop() {
        firefox?.foregroundChanged(false)
        if (::root.isInitialized) { lifetime.stop(); lifecycle("stop") }
        super.onStop()
    }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        if (::root.isInitialized) { lifecycle("configChange"); RosterPrivacy.apply(this); insetContent(root, 0) }
    }
    override fun onDestroy() {
        if (::root.isInitialized) lifecycle("destroy")
        if (lifetime.destroy()) {
            web?.let { root.removeView(it) }; browser?.destroy(); browser = null; firefox = null; web = null
        }
        lease?.close(); lease = null; screen?.close(); screen = null
        super.onDestroy()
    }
}
class RosterActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private val redraw = object : Runnable { override fun run() { show(); handler.postDelayed(this, 60_000) } }
    override fun onCreate(state: Bundle?) {
        PhoneTheme.install(this); super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        RosterPrivacy.apply(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; PhoneTheme.page(this) }
        PhoneTheme.page(root); PhoneUi.style(root); setContentView(ScrollView(this).apply { addView(root) }); insetContent(root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 61)
    }
    override fun onResume() { super.onResume(); RosterPrivacy.apply(this); if (::root.isInitialized) { handler.post(redraw); RosterWork.onOpen(this) } }
    override fun onPause() { handler.removeCallbacks(redraw); super.onPause() }
    private fun show() {
        if (!PhoneTheme.classic(this)) { showGlass(); return }
        root.removeAllViews(); root.addView(RosterChangeAnnunciator(this)); root.addView(action("Back") { finish() }); root.addView(label("ROSTER LINK", 26f))
        root.addView(label("NEXT DUTY", 18f)); root.addView(label(RosterDisplay.compact(this), 22f))
        RosterDisplay.next(this)?.let { root.addView(label(RosterDisplay.legs(it), 17f)) }
        root.addView(action("Skip next PREPARE") { RosterAlarms.skipNext(this); Toast.makeText(this, "Next PREPARE skipped", Toast.LENGTH_SHORT).show() })
        root.addView(action("Open eCrew") { startActivity(Intent(this, ECrewActivity::class.java)) })
        root.addView(action("Import roster PDF") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE), 60) })
        root.addView(action("Capture log") { startActivity(Intent(this, RosterLogActivity::class.java)) })
        root.addView(action("Roster alarms & refresh") { startActivity(Intent(this, RosterSettingsActivity::class.java)) })
        val roster = RosterStore.load(this) ?: return
        val stamp = Instant.ofEpochMilli(RosterStore.prefs(this).getLong("lastSuccess", 0)).atZone(AirportZones.zone("MNL")).format(DateTimeFormatter.ofPattern("HH:mm"))
        root.addView(label("updated $stamp", 16f)); RosterStore.stale(this)?.let { root.addView(label(it.first, 18f).apply { setTextColor(if (PhoneTheme.classic(this@RosterActivity)) it.second else PhoneTheme.staleColor(this@RosterActivity)) }) }
        val today = LocalDate.now(AirportZones.zone("MNL")); var day = roster.period.start
        while (day <= roster.period.end) {
            val date = day; val duties = roster.duties.filter { it.date == date }
            root.addView(label("${if (date == today) "TODAY · " else ""}${date.format(DateTimeFormatter.ofPattern("EEE dd/MM"))}${if (duties.any { it.memoFlag } || roster.memos[date] != null) "  ✉" else ""}", 20f).apply { if (date == today) setTextColor(PhoneTheme.caution(this@RosterActivity)) })
            duties.forEach { d ->
                root.addView(label(if (d.code == "CHECK") "⚠ check eCrew" else if (d.legs.isEmpty()) "${roster.legend[d.code] ?: d.code} ${d.reportLocal?.toLocalTime() ?: "ALL DAY"}${d.releaseLocal?.let { " – ${it.toLocalTime()}" }.orEmpty()}" else "RPT ${d.reportLocal?.toLocalTime()} · release ${d.releaseLocal?.toLocalTime()}${if (d.releaseEstimated) " (est)" else ""}\n${RosterDisplay.legs(d)}", 16f))
            }
            roster.memos[date]?.let { root.addView(label(it, 15f)) }; day = day.plusDays(1)
        }
    }
    private fun showGlass() {
        root.removeAllViews()
        root.addView(PhoneTheme.appBar(this, "Roster Link") { finish() })
        root.addView(RosterChangeAnnunciator(this))
        val hero = PhoneTheme.card(this)
        hero.addView(label("NEXT DUTY", 12f).apply { setTextColor(PhoneTheme.secondary(this@RosterActivity)); letterSpacing = .08f })
        val duty = RosterStore.load(this)?.duties.orEmpty().filter { it.reportInstant != null && it.reportInstant > Instant.now() }.minByOrNull { it.reportInstant!! } ?: RosterDisplay.next(this)
        hero.addView(label(duty?.reportLocal?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: duty?.code ?: "Link eCrew", 32f).apply { typeface = resources.getFont(com.mirror.app.R.font.inter_semibold) })
        val countdown = com.mirror.app.phone.ClockEcrewRoster.countdown(RosterStore.load(this), Instant.now())
        hero.addView(label(countdown ?: RosterDisplay.compact(this), 16f))
        duty?.let { hero.addView(label("${it.date.format(DateTimeFormatter.ofPattern("EEE dd MMM"))} · ${it.code}", 14f).apply { setTextColor(PhoneTheme.secondary(this@RosterActivity)) }) }
        duty?.let { if (it.legs.isNotEmpty()) hero.addView(label(RosterDisplay.legs(it), 16f)) }
        root.addView(hero)
        val actions = listOf<Pair<String, () -> Unit>>(
            "Open eCrew" to { startActivity(Intent(this, ECrewActivity::class.java)) },
            "Import roster PDF" to { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE), 60) },
            "Capture log" to { startActivity(Intent(this, RosterLogActivity::class.java)) },
            "Alarms & refresh" to { startActivity(Intent(this, RosterSettingsActivity::class.java)) },
            "Skip next PREPARE" to { RosterAlarms.skipNext(this); Toast.makeText(this, "Next PREPARE skipped", Toast.LENGTH_SHORT).show() },
            "Clock settings" to { startActivity(Intent(this, com.mirror.app.phone.PhoneSettingsActivity::class.java).putExtra("section", "calendar")) })
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { (title, work) -> row.addView(action(title, work), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            root.addView(row)
        }
        val roster = RosterStore.load(this) ?: run { root.addView(label("Link eCrew in Roster Link", 16f)); return }
        val stamp = Instant.ofEpochMilli(RosterStore.prefs(this).getLong("lastSuccess", 0)).atZone(AirportZones.zone("MNL")).format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))
        root.addView(label("Updated $stamp", 13f).apply { setTextColor(PhoneTheme.secondary(this@RosterActivity)) })
        RosterStore.stale(this)?.let { root.addView(label(it.first, 15f).apply { setTextColor(if (PhoneTheme.classic(this@RosterActivity)) it.second else PhoneTheme.staleColor(this@RosterActivity)) }) }
        val today = LocalDate.now(AirportZones.zone("MNL")); var date = roster.period.start
        while (date <= roster.period.end) {
            val card = PhoneTheme.card(this, date == today)
            card.addView(label("${if (date == today) "TODAY · " else ""}${date.format(DateTimeFormatter.ofPattern("EEE dd MMM"))}", 15f).apply { if (date == today) setTextColor(PhoneTheme.accent(this@RosterActivity)) })
            val duties = roster.duties.filter { it.date == date }
            duties.forEach { d ->
                val lines = com.mirror.app.phone.ClockEcrewRoster.lines(d).joinToString("\n") { it.text }
                card.addView(label(lines, 16f))
                roster.legend[d.code]?.let { card.addView(label(it, 13f).apply { setTextColor(PhoneTheme.secondary(this@RosterActivity)) }) }
            }
            roster.memos[date]?.let { card.addView(label("✉ $it", 13f)) }
            if (duties.any { it.memoFlag } && !roster.memos.containsKey(date)) card.addView(label("✉ memo", 13f))
            root.addView(card); date = date.plusDays(1)
        }
    }
    @Deprecated("Activity result compatibility") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 60 && result == RESULT_OK) data?.data?.let { RosterImport.read(this, it) { show() } }
    }
}
object RosterImport {
    fun read(a: Activity, uri: Uri, done: (RosterImportRouting.Result) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching {
                val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(RosterFetcher.LIMIT) }
                    ?: return@runCatching RosterImportRouting.Result.FAILED
                RosterImportRouting.route(bytes, { pdf ->
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(a.applicationContext)
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf).use { doc ->
                        require(doc.numberOfPages in 1..20)
                        com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
                    }
                }, { pdf -> RosterStore.accept(a, pdf) })
            }.getOrDefault(RosterImportRouting.Result.FAILED)
            if (result != RosterImportRouting.Result.IMPORTED) CaptureLog.add(a, "IMPORT", if (result == RosterImportRouting.Result.NOT_ROSTER) "not an eCrew roster; private roster retained" else "failed; private roster retained")
            a.runOnUiThread {
                Toast.makeText(a, when (result) {
                    RosterImportRouting.Result.IMPORTED -> "Roster imported"
                    RosterImportRouting.Result.NOT_ROSTER -> "Not an eCrew roster"
                    RosterImportRouting.Result.FAILED -> "Import failed — see capture log"
                }, Toast.LENGTH_LONG).show()
                done(result)
            }
            executor.shutdown()
        }
    }
}
class RosterImportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        PhoneTheme.install(this); super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        @Suppress("DEPRECATION") val uri = if (intent.action == Intent.ACTION_VIEW) intent.data else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
        if (uri == null || uri.scheme != "content") { finish(); return }
        setContentView(label("Importing roster…"))
        RosterImport.read(this, uri) { result ->
            if (result == RosterImportRouting.Result.IMPORTED) {
                startActivity(Intent(this, RosterActivity::class.java)); finish()
            } else if (result == RosterImportRouting.Result.NOT_ROSTER) {
                AlertDialog.Builder(this).setMessage("This PDF is not an eCrew roster.")
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .setPositiveButton("Open in another app") { _, _ -> openElsewhere(uri) }
                    .setOnCancelListener { finish() }.show()
            } else finish()
        }
    }
    private fun openElsewhere(uri: Uri) {
        val viewer = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("PDF", uri) }
        val excluded = arrayOf(ComponentName(this, "com.mirror.app.core.RosterShareActivity"), ComponentName(this, RosterImportActivity::class.java))
        val chooser = Intent.createChooser(viewer, "Open in another app").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
        try { startActivity(chooser) } catch (_: ActivityNotFoundException) { Toast.makeText(this, "No other PDF app available", Toast.LENGTH_LONG).show() }
        finish()
    }
}
class RosterLogActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        PhoneTheme.install(this); super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        RosterPrivacy.apply(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(PhoneTheme.appBar(this, "Capture log") { finish() }); val log = label(CaptureLog.read(this), 12f)
        root.addView(action("Copy log") { copyRosterLog() })
        root.addView(action("Share log as text file") { shareRosterLog() })
        root.addView(action("Share last PDF") {
            AlertDialog.Builder(this).setMessage("This PDF contains private crew information. Share it only with a recipient you trust.").setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                val file = File(RosterStore.dir(this), "latest.pdf")
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.rosterfiles", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share roster PDF"))
                }
            }.show()
        })
        if (com.mirror.app.BuildConfig.ROSTER_ENABLED) root.addView(action("Share captured schedule data") {
            AlertDialog.Builder(this).setMessage("Captured schedule data contains private crew information, including duty details. Share it only with a recipient you trust.")
                .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                    val file = EcrewScheduleData.archive(this)
                    if (file == null) Toast.makeText(this, "No schedule data captured yet", Toast.LENGTH_LONG).show()
                    else {
                        val uri = FileProvider.getUriForFile(this, "$packageName.rosterfiles", file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share captured schedule data"))
                    }
                }.show()
        })
        root.addView(action("Clear data") {
            AlertDialog.Builder(this).setMessage("Delete private roster PDFs, parsed data, capture log and eCrew session?").setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                EcrewBrowsers.current?.pauseForLocalClear()
                val savedMode = EcrewBrowserModes.selected(this)
                val savedEngine = EcrewEngine.selected(this)
                RosterStore.clear(this)
                EcrewBrowserModes.save(this, savedMode)
                RosterStore.prefs(this).edit().putString("engine", savedEngine.name).apply()
                File(cacheDir, "roster-logs").deleteRecursively()
                EcrewFirefox.clearLocal(this) { EcrewStorage.clearLocal(this) { log.text = CaptureLog.read(this); Toast.makeText(this, "Local eCrew and roster data cleared", Toast.LENGTH_SHORT).show() } }
            }.show()
        }); root.addView(log); PhoneTheme.groupSettings(root); PhoneTheme.page(root); PhoneUi.style(root); setContentView(ScrollView(this).apply { addView(root) }); insetContent(root)
    }
}
class RosterSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) { PhoneTheme.install(this); super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }; RosterPrivacy.apply(this); show() }
    private fun show() {
        RosterPrivacy.apply(this)
        val p = RosterStore.prefs(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(PhoneTheme.appBar(this, "Roster alarms & refresh") { finish() })
        root.addView(label("Connection & refresh", 22f))
        root.addView(label(EcrewWebViewInfo.text(this), 14f))
        val selectedEngine = EcrewEngine.selected(this)
        root.addView(action("Roster engine: ${selectedEngine.label}") {
            AlertDialog.Builder(this).setSingleChoiceItems(EcrewEngine.entries.map { it.label }.toTypedArray(), selectedEngine.ordinal) { dialog, index ->
                EcrewBrowsers.current?.pauseForLocalClear()
                RosterStore.prefs(this).edit().putString("engine", EcrewEngine.entries[index].name).apply()
                EcrewBrowserModes.save(this, EcrewBrowserMode.CLEAN)
                RosterWork.configure(this)
                Toast.makeText(this, "Close and reopen eCrew to apply the engine", Toast.LENGTH_LONG).show()
                dialog.dismiss(); show()
            }.show()
        })
        root.addView(action("Refresh interval: ${p.getInt("interval", 30)} min") {
            AlertDialog.Builder(this).setItems(arrayOf("15 min", "30 min", "60 min")) { _, i -> p.edit().putInt("interval", listOf(15, 30, 60)[i]).apply(); RosterWork.configure(this); show() }.show()
        })
        fun toggle(text: String, key: String, default: Boolean) { root.addView(PhoneTheme.switch(this).apply { this.text = text; isChecked = p.getBoolean(key, default); setOnCheckedChangeListener { _, value -> p.edit().putBoolean(key, value).apply(); RosterPrivacy.apply(this@RosterSettingsActivity); RosterAlarms.reschedule(this@RosterSettingsActivity) } }) }
        root.addView(label("Privacy & clock", 22f))
        toggle("Block screenshots on roster screens", "blockScreenshots", false); toggle("Next duty line", "nextDutyLine", true); toggle("Use system alarm sound", "systemSound", false)
        root.addView(label("Duty types", 22f))
        toggle("Flight duties", "type-FLIGHT", true); toggle("Airport standby AS", "type-AS", true); toggle("Home standby HSA", "type-HSA", false)
        RosterStore.load(this)?.duties?.filter { it.type == DutyType.OTHER && it.reportInstant != null }?.map { it.code }?.distinct()?.forEach { toggle("Timed duty $it", "type-$it", true) }
        root.addView(label("Roster alarms", 22f))
        val alarms = RosterAlarms.definitions(this)
        alarms.forEach { alarm ->
            root.addView(PhoneTheme.switch(this).apply { text = "${alarm.label} · ${alarm.offset / 60}:${(alarm.offset % 60).toString().padStart(2, '0')} before report"; isChecked = alarm.enabled; setOnCheckedChangeListener { _, on -> RosterAlarms.save(this@RosterSettingsActivity, RosterAlarms.definitions(this@RosterSettingsActivity).map { if (it.id == alarm.id) it.copy(enabled = on) else it }) } })
            root.addView(action("Edit ${alarm.label}") { edit(alarm) })
            if (alarm.id != 1) root.addView(action("Remove ${alarm.label}") { RosterAlarms.save(this, RosterAlarms.definitions(this).filter { it.id != alarm.id }); show() })
        }
        if (alarms.size < 5) root.addView(action("+ Add roster alarm") { edit(RosterAlarm((alarms.maxOfOrNull { it.id } ?: 0) + 1, "Roster alarm")) })
        root.addView(label("Android permissions", 22f))
        if (Build.VERSION.SDK_INT >= 31) root.addView(action("Exact alarm access") { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) })
        if (Build.VERSION.SDK_INT >= 34) root.addView(action("Full-screen alarm access") { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) })
        root.addView(action("Notification settings") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) })
        PhoneTheme.groupSettings(root); PhoneTheme.page(root); PhoneUi.style(root); setContentView(ScrollView(this).apply { addView(root) }); insetContent(root)
    }
    private fun edit(alarm: RosterAlarm) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; val name = EditText(this).apply { setText(alarm.label) }; box.addView(name)
        val offset = NumberPicker(this).apply { minValue = 3; maxValue = 48; value = alarm.offset / 5; displayedValues = (3..48).map { "${it * 5 / 60}:${(it * 5 % 60).toString().padStart(2, '0')} before report" }.toTypedArray() }; box.addView(offset)
        AlertDialog.Builder(this).setTitle("Roster alarm").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val values = RosterAlarms.definitions(this).filter { it.id != alarm.id } + alarm.copy(label = name.text.toString().trim().ifEmpty { "PREPARE" }, offset = offset.value * 5)
            RosterAlarms.save(this, values); show()
        }.show()
    }
}

private fun Context.copyRosterLog() {
    getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Mirror capture log", CaptureLog.read(this)))
}
private fun Activity.shareRosterLog() {
    val directory = File(cacheDir, "roster-logs").apply { mkdirs() }
    val file = File(directory, "mirror-ecrew-log.txt").apply { writeText(CaptureLog.read(this@shareRosterLog), Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(this, "$packageName.rosterfiles", file)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share eCrew diagnostic log"))
}

