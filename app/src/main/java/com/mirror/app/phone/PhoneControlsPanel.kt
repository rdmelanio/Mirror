package com.mirror.app.phone

import android.app.*
import android.content.Intent
import android.os.*
import android.widget.*
import com.mirror.app.core.*

class PhoneControlsPanel(private val activity: Activity, private val content: LinearLayout, private val category: String) {
    private val security get() = PhoneSecurity.get(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var pairingDialog: AlertDialog? = null
    private val prefs get() = activity.mirrorPreferences()
    private var front = prefs.getBoolean("phoneFront", false)
    private var fullHd = prefs.getBoolean("phoneFullHd", false)
    private val locked = mutableListOf<RadioGroup>()
    private val status = activity.label("", 15f)
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    init {
        if (category == "camera") {
            content.addView(activity.label("Camera & streaming", 25f))
            content.addView(PhoneTheme.switch(activity, checkbox = true).apply {
                text = "Standby · camera wakes when a paired TV connects"; isChecked = prefs.getBoolean("standby", true)
                setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("standby", value).apply() }
            })
            content.addView(activity.label("Camera")); val cameras = choices(listOf("Rear", "Front"), if (front) 1 else 0) { front = it == 1; save() }
            content.addView(cameras); locked += cameras
            content.addView(activity.label("Resolution")); val resolution = choices(listOf("720p", "1080p"), if (fullHd) 1 else 0) { fullHd = it == 1; save() }
            content.addView(resolution); locked += resolution
            content.addView(activity.label("Stream orientation"))
            val modes = listOf("landscape", "portrait", "auto")
            content.addView(choices(listOf("Landscape", "Portrait", "Auto"), modes.indexOf(prefs.getString("streamOrientation", "landscape")).coerceAtLeast(0)) {
                prefs.edit().putString("streamOrientation", modes[it]).apply()
            })
            content.addView(status); status.setTextIsSelectable(true)
        } else {
            content.addView(activity.label("Pairing & security", 25f))
            content.addView(activity.action("Pair new TV") { showPairing() })
            content.addView(activity.action("Paired devices") { showDevices() })
            val browser = activity.action("Allow browser viewing: ${if (security.browserEnabled()) "On" else "Off"}") {}
            browser.setOnClickListener {
                if (security.browserEnabled()) { security.setBrowserPassword(null); browser.text = "Allow browser viewing: Off" }
                else {
                    val password = EditText(activity).apply { hint = "Set a browser password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    val dialog = AlertDialog.Builder(activity).setTitle("Allow browser viewing")
                        .setMessage("Use username mirror and this password in Chrome. Video is not encrypted yet. Browser controls are disabled.")
                        .setView(password).setPositiveButton("Enable", null).setNegativeButton("Cancel", null).create()
                    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (password.text.isEmpty()) password.error = "Enter a password"
                        else { security.setBrowserPassword(password.text.toString()); browser.text = "Allow browser viewing: On"; dialog.dismiss() }
                    } }; dialog.show()
                }
            }; content.addView(browser)
        }
    }
    private fun choices(names: List<String>, selected: Int, changed: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        names.forEachIndexed { index, name -> group.addView(RadioButton(activity).apply {
            id = android.view.View.generateViewId(); text = name; tag = index; isChecked = index == selected
        }) }
        group.setOnCheckedChangeListener { _, id -> group.findViewById<RadioButton>(id)?.let { changed(it.tag as Int) } }
        return group
    }
    private fun save() { prefs.edit().putBoolean("phoneFront", front).putBoolean("phoneFullHd", fullHd).apply() }
    private fun update() {
        locked.forEach { group -> for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !CameraService.active }
        val address = LocalNetwork.address(activity)
        val m = CameraService.stats
        status.text = if (address == null) "Connect to the same Wi-Fi as the TV" else "Stream address: http://$address:8080/video"
        if (CameraService.active) status.append(String.format(java.util.Locale.US, "\nCamera %.0f fps · Encode %.0f ms\nSending %.0f fps · %d viewers", m.cameraFps, m.encodeMs, m.sendFps, m.clients.get()))
        if (CameraService.active && prefs.getString("streamOrientation", "landscape") == "landscape" && CameraService.physicalPortrait) status.append("\nTip: mount the phone sideways for a wider view")
        if (CameraService.standbyFallback) status.append("\nStandby is unavailable on this device; the camera stays running.")
        CameraService.error?.let { status.append("\n$it") }; CameraService.note?.let { status.append("\n$it") }
    }
    private fun showPairing() {
        pairingDialog?.dismiss()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val code = activity.label("", 32f); content.addView(code)
        content.addView(activity.label("On your TV, enter this code. It is valid for 2 minutes while this screen stays open."))
        var shown: String? = security.showCode()
        val regenerate = activity.action("Regenerate") { shown = security.showCode() }; content.addView(regenerate)
        val dialog = AlertDialog.Builder(activity).setTitle("Pair new TV").setView(content).setNegativeButton("Close", null).create()
        val tick = object : Runnable {
            override fun run() {
                val lock = security.lockSeconds(); val remaining = security.remainingSeconds()
                code.text = if (lock > 0) "Too many attempts - wait ${lock}s" else if (remaining == 0L) "Code expired - Regenerate" else "$shown\nExpires in ${remaining}s"
                regenerate.isEnabled = lock == 0L
                handler.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener { security.hideCode(); handler.removeCallbacks(tick); pairingDialog = null }
        PhoneUi.style(content); pairingDialog = dialog; dialog.show(); handler.post(tick); regenerate.requestFocus()
    }
    private fun showDevices() {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(content) }
        fun render() {
            content.removeAllViews()
            val devices = security.list()
            if (devices.isEmpty()) content.addView(activity.label("No paired devices"))
            devices.forEach { device ->
                val seen = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(device.lastSeen))
                content.addView(activity.label("${device.name}\nLast seen: $seen"))
                content.addView(activity.action("Remove ${device.name}") { security.remove(device.hash); render() })
            }
        }
        render(); AlertDialog.Builder(activity).setTitle("Paired devices").setView(scroll).setNegativeButton("Close", null).show()
    }
    fun start() { if (category == "camera") handler.post(refresh) }
    fun stop() { pairingDialog?.dismiss(); handler.removeCallbacksAndMessages(null) }
}

