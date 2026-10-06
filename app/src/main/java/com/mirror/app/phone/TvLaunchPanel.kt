package com.mirror.app.phone

import android.app.AlertDialog
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import com.mirror.app.core.*

internal class TvLaunchPanel(private val activity: ComponentActivity, private val content: LinearLayout) {
    private val buttons = mutableListOf<Button>()
    private lateinit var state: TextView
    private val changed: () -> Unit = { refresh() }
    private var active = false
    private var refreshIcon: (() -> Unit)? = null
    init {
        content.addView(activity.label("TV / ONE-TAP LAUNCH", 23f))
        content.addView(activity.label("Pair once here, separately from Bugjaeger. Tap the white mirror icon to wake your TV and open Mirror. The connection closes afterward."))
        content.addView(activity.label("On the TV: Developer options → Wireless debugging → Pair device with pairing code. Keep that dialog open while pairing. The pairing port and main connection port are different."))
        val prefs = TvLauncher.prefs(activity)
        fun input(title: String, initial: String, numeric: Boolean = false, secret: Boolean = false): EditText {
            content.addView(activity.label(title))
            return EditText(activity).apply {
                setSingleLine(); setText(initial)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or (if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                content.addView(this)
            }
        }
        val address = input("TV IP address (main Wireless debugging screen)", prefs.getString("host", "").orEmpty())
        val connection = input("Connection port (main screen; e.g. 40795)", prefs.getInt("port", 0).takeIf { it > 0 }?.toString().orEmpty(), true)
        val pairing = input("Pairing port (temporary pairing dialog)", "", true)
        val code = input("Six-digit pairing code (temporary)", "", true, true)
        fun action(title: String, work: () -> Unit) {
            val button = activity.action(title) {
                runCatching(work).onFailure { Toast.makeText(activity, it.message ?: "Check the TV details.", Toast.LENGTH_LONG).show() }
            }; buttons += button; content.addView(button)
        }
        action("PAIR MIRROR WITH TV") {
            check(!TvLauncher.running)
            val host = TvTarget.host(address.text.toString()); val connectPort = TvTarget.port(connection.text.toString())
            val pairPort = TvTarget.port(pairing.text.toString()); val digits = code.text.toString().trim()
            require(digits.matches(Regex("[0-9]{6}"))) { "Enter the TV's six-digit pairing code." }
            code.setText(""); TvLauncher.pair(activity, host, pairPort, digits, connectPort)
        }
        action("UPDATE ADDRESS / CONNECTION PORT") {
            check(!TvLauncher.running)
            val host = TvTarget.host(address.text.toString()); val port = TvTarget.port(connection.text.toString())
            prefs.edit().putString("host", host).putInt("port", port).apply()
            Toast.makeText(activity, "TV address saved. Pairing is retained.", Toast.LENGTH_SHORT).show(); refresh()
        }
        action("TEST · WAKE TV & OPEN MIRROR") { TvLauncher.launch(activity) }
        action("FORGET TV PAIRING") {
            AlertDialog.Builder(activity).setTitle("Forget this TV?")
                .setMessage("Mirror will need a new pairing code. You can also remove Mirror Clock from the TV's paired devices list.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> TvLauncher.forget(activity) }.show()
        }
        val show = CheckBox(activity).apply { text = "Show white mirror icon on clock"; isChecked = ClockSettings.load(activity).tvIcon }
        show.setOnCheckedChangeListener { _, value -> val s = ClockSettings.load(activity); if (s.tvIcon != value) s.copy(tvIcon = value).save(activity) }; content.addView(show)
        val sizeLabel = activity.label(""); content.addView(sizeLabel)
        val size = SeekBar(activity).apply { max = 72; progress = ClockSettings.load(activity).tvIconSize - 24 }
        fun caption() { sizeLabel.text = "Mirror icon size: ${size.progress + 24} dp" }; caption()
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) {
                caption(); if (user) ClockSettings.load(activity).copy(tvIconSize = progress + 24).save(activity)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        }); content.addView(size)
        refreshIcon = { val s = ClockSettings.load(activity); show.isChecked = s.tvIcon; size.progress = s.tvIconSize - 24; caption() }
        content.addView(activity.action("RESET MIRROR ICON POSITION") {
            val s = ClockSettings.load(activity); s.copy(positions = s.positions - "tv_launch").save(activity)
        })
        content.addView(activity.label("On the clock: tap to launch; hold the icon briefly to select and drag; pinch while selected to resize only the icon. Turn on layout editing under Style & layout. Hold empty space for two seconds to exit."))
        content.addView(activity.label("Keep phone and TV on the same home network. Discovery handles changing ports and IP addresses when advertised by the TV. If discovery is blocked, update the address/connection port above. No persistent ADB connection, polling or additional wake lock is used."))
        state = activity.label(""); content.addView(state); refresh()
    }
    private fun refresh() {
        refreshIcon?.invoke()
        buttons.forEach { it.isEnabled = !TvLauncher.running }
        state.text = "${if (TvLauncher.paired(activity)) "TV PAIRED" else "TV NOT PAIRED"}\n${TvLauncher.status}"
    }
    fun start() { if (!active) { active = true; TvLauncher.listeners.add(changed) }; refresh() }
    fun stop() { active = false; TvLauncher.listeners.remove(changed) }
}
