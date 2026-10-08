package com.mirror.app.phone.roster

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.mirror.app.core.*
import org.json.*

data class RosterBannerState(val revision: Long = 0, val acknowledged: Long = 0,
    val summaries: List<String> = emptyList(), val pending: Boolean = false,
    val changeRevision: Long = revision, val pendingRevision: Long = 0) {
    val visible get() = changeRevision > acknowledged || (pending && pendingRevision > acknowledged)
    fun update(changes: List<String>, pendingChanges: Boolean): RosterBannerState {
        val newPending = pendingChanges && !pending
        val next = if (changes.isNotEmpty() || newPending) revision + 1 else revision
        return copy(revision = next, changeRevision = if (changes.isNotEmpty()) next else changeRevision,
            pendingRevision = if (newPending) next else pendingRevision, pending = pendingChanges,
            summaries = if (changes.isEmpty()) summaries else ((if (visible) summaries else emptyList()) + changes).distinct().takeLast(100))
    }
    fun acknowledge(shownRevision: Long) = copy(acknowledged = maxOf(acknowledged, minOf(shownRevision, revision)))
}
object RosterChanges {
    @Synchronized fun state(c: Context): RosterBannerState = runCatching {
        val j = JSONObject(RosterStore.prefs(c).getString("changeBanner", "{}").orEmpty()); val a = j.optJSONArray("summaries") ?: JSONArray()
        val messages = (0 until a.length()).map { a.getString(it) }.filterNot { it == "Crew scheduling changed your roster — open eCrew to review & confirm" }
        RosterBannerState(j.optLong("revision"), j.optLong("acknowledged"), messages, j.optBoolean("pending"), j.optLong("changeRevision", if (messages.isEmpty()) 0 else j.optLong("revision")), j.optLong("pendingRevision", if (j.optBoolean("pending")) j.optLong("revision") else 0))
    }.getOrDefault(RosterBannerState())
    private fun save(c: Context, state: RosterBannerState) {
        val a = JSONArray(); state.summaries.forEach { a.put(it) }
        RosterStore.prefs(c).edit().putString("changeBanner", JSONObject().put("revision", state.revision).put("acknowledged", state.acknowledged).put("summaries", a).put("pending", state.pending).put("changeRevision", state.changeRevision).put("pendingRevision", state.pendingRevision).toString()).apply()
    }
    @Synchronized fun update(c: Context, changes: List<String> = emptyList(), pending: Boolean = RosterStore.prefs(c).getBoolean("pendingChanges", false)) {
        val updated = state(c).update(changes, pending); save(c, updated)
        if (!pending) c.getSystemService(android.app.NotificationManager::class.java).cancel(603)
    }
    @Synchronized fun acknowledge(c: Context, shownRevision: Long) {
        val updated = state(c).acknowledge(shownRevision); save(c, updated)
        if (!updated.visible) c.getSystemService(android.app.NotificationManager::class.java).let { it.cancel(601); it.cancel(603) }
    }
}
class RosterChangesActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        RosterPrivacy.apply(this)
        val banner = RosterChanges.state(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(label("ROSTER CHANGE", 25f).apply { setTextColor(0xFFFFB000.toInt()) })
        if (banner.pending) root.addView(label("Pending confirmation in eCrew", 18f))
        banner.summaries.forEach { root.addView(label(it, 18f)) }
        root.addView(label("Review and confirm changes yourself in the official eCrew app.", 17f))
        root.addView(action("Close") { finish() })
        setContentView(ScrollView(this).apply { addView(root) }); insetContent(root, 12)
        RosterChanges.acknowledge(this, banner.revision)
    }
}

/** Attached views own their ticker, including the phone home and roster page. */
class RosterChangeAnnunciator(c: Context) : TextView(c) {
    private val preferences = RosterStore.prefs(c)
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "changeBanner") update() }
    private val tick = Runnable { update() }
    init {
        text = "ROSTER CHANGE"; textSize = 22f; setTextColor(0xFFFFB000.toInt()); setPadding(16, 12, 16, 12)
        contentDescription = "Roster change — tap for summary"
        setOnClickListener { context.startActivity(android.content.Intent(context, RosterChangesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        update()
    }
    private fun update() {
        removeCallbacks(tick)
        visibility = if (RosterStore.phone(context) && RosterChanges.state(context).visible) VISIBLE else GONE
        alpha = if (System.currentTimeMillis() % 1000 < 500) 1f else 0.18f
        if (isAttachedToWindow && visibility == VISIBLE) postDelayed(tick, 500)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); preferences.registerOnSharedPreferenceChangeListener(listener); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); preferences.unregisterOnSharedPreferenceChangeListener(listener); super.onDetachedFromWindow() }
}
