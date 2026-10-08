package com.mirror.app.phone.roster

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.mirror.app.core.*
import org.json.*

data class RosterBannerState(val revision: Long = 0, val acknowledged: Long = 0,
    val summaries: List<String> = emptyList(), val pending: Boolean = false) {
    val visible get() = revision > acknowledged
    fun update(changes: List<String>, pendingChanges: Boolean): RosterBannerState {
        val messages = changes + if (pendingChanges && !pending) listOf("Crew scheduling changed your roster — open eCrew to review & confirm") else emptyList()
        return if (messages.isEmpty()) copy(pending = pendingChanges) else copy(revision = revision + 1,
            summaries = (if (visible) summaries else emptyList()).plus(messages).distinct().takeLast(100), pending = pendingChanges)
    }
    fun acknowledge(shownRevision: Long) = copy(acknowledged = maxOf(acknowledged, minOf(shownRevision, revision)))
}
object RosterChanges {
    @Synchronized fun state(c: Context): RosterBannerState = runCatching {
        val j = JSONObject(RosterStore.prefs(c).getString("changeBanner", "{}").orEmpty()); val a = j.optJSONArray("summaries") ?: JSONArray()
        RosterBannerState(j.optLong("revision"), j.optLong("acknowledged"), (0 until a.length()).map { a.getString(it) }, j.optBoolean("pending"))
    }.getOrDefault(RosterBannerState())
    private fun save(c: Context, state: RosterBannerState) {
        val a = JSONArray(); state.summaries.forEach { a.put(it) }
        RosterStore.prefs(c).edit().putString("changeBanner", JSONObject().put("revision", state.revision).put("acknowledged", state.acknowledged).put("summaries", a).put("pending", state.pending).toString()).apply()
    }
    @Synchronized fun update(c: Context, changes: List<String> = emptyList(), pending: Boolean = RosterStore.prefs(c).getBoolean("pendingChanges", false)) { save(c, state(c).update(changes, pending)) }
    @Synchronized fun acknowledge(c: Context, shownRevision: Long) { save(c, state(c).acknowledge(shownRevision)) }
}
class RosterChangesActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); if (!RosterStore.phone(this)) { finish(); return }
        RosterPrivacy.apply(this)
        val banner = RosterChanges.state(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(label("ROSTER CHANGED", 25f).apply { setTextColor(0xFFFFB000.toInt()) })
        banner.summaries.forEach { root.addView(label(it, 18f)) }
        root.addView(action("Open eCrew to review") { startActivity(android.content.Intent(this, ECrewActivity::class.java)) })
        root.addView(action("Close") { finish() })
        setContentView(ScrollView(this).apply { addView(root) }); insetContent(root, 12)
        RosterChanges.acknowledge(this, banner.revision)
    }
}
