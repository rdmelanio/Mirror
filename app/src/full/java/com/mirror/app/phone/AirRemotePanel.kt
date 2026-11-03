package com.mirror.app.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.widget.*
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.mirror.app.core.*

internal class AirRemotePanel(private val activity: ComponentActivity, content: LinearLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = activity.label("")
    private lateinit var testCommand: android.view.View
    private val refresh = object : Runnable { override fun run() { state.text = "${AirRemoteTv.status}\n${AirRemote.diagnostics()}"; testCommand.isEnabled=AirRemoteTv.connected; testCommand.alpha=if(AirRemoteTv.connected) 1f else .45f; handler.postDelayed(this, 500) } }
    init {
        AirRemoteTv.init(activity)
        val p = AirRemoteTv.prefs(activity)
        content.addView(activity.label("AIR REMOTE", 23f))
        content.addView(activity.label("Hold an open palm still for two seconds to claim control, then relax your hand briefly before gesturing. Swipe with your palm for directions; fist = OK, victory = Back, thumb up = Home. Release each gesture before repeating. Tracks that hand only; pauses for ambiguity and disarms on loss or 30 seconds without a command. Standby ~3 fps, active ~12 fps, adapted for performance. Uses the running camera even outside Mirror on TV."))
        val enable = PhoneTheme.switch(activity).apply { text = "Enable Air Remote"; isChecked = AirRemote.enabled(activity) }
        enable.setOnCheckedChangeListener { _, checked ->
            if (checked && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                enable.isChecked = false; activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 8090)
                Toast.makeText(activity, "Allow camera access, then enable Air Remote again", Toast.LENGTH_LONG).show()
            } else {
                p.edit().putBoolean("enabled", checked).apply()
                if (checked) {
                    val settings = activity.mirrorPreferences()
                    runCatching { activity.startForegroundService(Intent(activity, CameraService::class.java)
                        .putExtra("front", settings.getBoolean("phoneFront", false)).putExtra("fullHd", settings.getBoolean("phoneFullHd", false))) }
                        .onFailure { p.edit().putBoolean("enabled", false).apply(); enable.isChecked = false; Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                } else AirRemoteTv.stop()
            }
        }
        content.addView(enable)
        content.addView(activity.label("Pair once; submitting the code connects automatically. Saved pairing survives updates. Retry uses the saved certificate, including after an IP change."))
        content.addView(activity.label("TV local IPv4 address (Remote Service, not Wireless debugging)"))
        val address = EditText(activity).apply { setSingleLine(); setText(p.getString("host", TvLauncher.prefs(activity).getString("host", ""))) }; content.addView(address)
        fun button(text: String, action: () -> Unit) { content.addView(activity.action(text) { runCatching(action).onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() } }) }
        button("PAIR TV · SHOW CODE ON TV") { AirRemoteTv.pair(activity, address.text.toString()) }
        val code = EditText(activity).apply { hint = "Six-character TV code"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }; content.addView(code)
        button("SUBMIT PAIRING CODE") { val value = code.text.toString(); code.setText(""); AirRemoteTv.code(value) }
        button("RECONNECT / RETRY SAVED TV") { AirRemoteTv.retry(activity, address.text.toString()) }
        testCommand=activity.action("TEST TV COMMAND · D-PAD RIGHT") {
            Toast.makeText(activity, AirRemoteTv.send(AirGestures.Command.RIGHT), Toast.LENGTH_SHORT).show()
        }.also { it.isEnabled=AirRemoteTv.connected;content.addView(it) }
        content.addView(activity.label("Sensitivity: lower = larger deliberate swipes. Calibrate below your TV by testing each direction."))
        content.addView(SeekBar(activity).apply {
            max = 100; progress = p.getInt("sensitivity", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user) p.edit().putInt("sensitivity", value).apply() }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        content.addView(PhoneTheme.switch(activity).apply { text = "Mirror horizontal gestures (viewer-facing camera)"; isChecked = p.getBoolean("mirror", true); setOnCheckedChangeListener { _, value -> p.edit().putBoolean("mirror", value).apply() } })
        content.addView(state)
        content.addView(activity.label("Requires Android TV Remote Service and the same Wi-Fi. Pairing is separate from TV launch / ADB. Opening Mirror Clock starts the enabled camera service while the phone UI is visible. After reboot or force-stop, reopen Mirror Clock once; Android may require foreground access. On TV, enable Temporary Air Remote feedback in Quick Menu and allow overlays once. Feedback requires the existing phone-camera pairing and shows transmission, not proof of TV execution."))
    }
    fun start() { handler.removeCallbacks(refresh); refresh.run() }
    fun stop() { handler.removeCallbacks(refresh) }
}
