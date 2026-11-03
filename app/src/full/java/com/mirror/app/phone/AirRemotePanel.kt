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
    private val refresh = object : Runnable { override fun run() { state.text = "${AirRemoteTv.status}\n${AirRemote.diagnostics()}"; testCommand.isEnabled=AirRemoteTv.connected && !AirRemote.calibration; testCommand.alpha=if(AirRemoteTv.connected) 1f else .45f; handler.postDelayed(this, 500) } }
    init {
        AirRemoteTv.init(activity)
        val p = AirRemoteTv.prefs(activity)
        content.addView(activity.label("AIR REMOTE", 23f))
        content.addView(activity.label("Household Mode: hold an open palm about one second to claim or deliberately take over after the current controller stops. Default Air Joystick: hold index upward and move the whole hand; return to center to rearm. Directional Pointing is selectable; thumb up = OK, thumb down = Back, victory = Home (longer hold). Fist or open palm releases a held command. Tracking remains claimed through tracking pauses until the configured inactivity timeout. Eco sampling accelerates during activation and active control, limited by actual processing and temperature."))
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
        content.addView(activity.label("Session inactivity timeout · Household Mode"))
        val timeouts=listOf(30_000L,60_000L,120_000L,300_000L)
        content.addView(Spinner(activity).apply {
            adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("30 seconds","1 minute (default)","2 minutes","5 minutes"))
            setSelection(timeouts.indexOf(p.getLong("session_timeout",60_000)).coerceAtLeast(0))
            onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { p.edit().putLong("session_timeout",timeouts[position]).apply() }
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            }
        })
        button("INSPECT / COPY RECENT DIAGNOSTICS") {
            val text=AirRemote.history.text()
            android.app.AlertDialog.Builder(activity).setTitle("Air Remote · last 60 seconds").setMessage(text)
                .setPositiveButton("Copy") { _,_ -> activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Mirror diagnostics",text)) }
                .setNegativeButton("Close",null).show()
        }
        val confidenceLabel=activity.label("")
        fun confidenceText(value:Int) { confidenceLabel.text="Minimum Gesture Confidence: $value%" }
        confidenceText(AirConfidence.percent(p.getInt("confidence",65)))
        content.addView(confidenceLabel)
        content.addView(SeekBar(activity).apply {
            max=9;progress=(AirConfidence.percent(p.getInt("confidence",65))-50)/5
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) { if(user) { val percent=50+5*value;p.edit().putInt("confidence",percent).apply();confidenceText(percent) } }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Calibration · recognize without sending commands";isChecked=false
            setOnCheckedChangeListener { _,value -> AirRemote.calibration=value }
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Legacy Gesture Mode · swipes / fist OK";isChecked=p.getBoolean("legacy",false)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean("legacy",v).apply() }
        })
        fun choices(title:String,key:String,values:List<String>,labels:List<String>,default:String) {
            content.addView(activity.label(title))
            content.addView(Spinner(activity).apply {
                adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,labels)
                setSelection(values.indexOf(p.getString(key,default)).coerceAtLeast(0))
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { if(p.getString(key,default)!=values[position]) AirRemoteTv.discardPending();p.edit().putString(key,values[position]).apply() }
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                }
            })
        }
        choices("Navigation Mode (Legacy setting remains independent)","navigation",listOf("joystick","pointing"),listOf("Air Joystick (default)","Directional Pointing"),"joystick")
        choices("Smart Hand Lock", "hand_lock",listOf("auto","wide","distance"),listOf("Auto (default)","Standard / wide scene","Long-distance optimized"),"auto")
        val joyLabel=activity.label("")
        fun joyText(v:Int) { joyLabel.text="Joystick sensitivity: $v · higher = smaller movement" }
        joyText(p.getInt("joystick_sensitivity",50));content.addView(joyLabel)
        content.addView(SeekBar(activity).apply {
            max=100;progress=p.getInt("joystick_sensitivity",50)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,v:Int,user:Boolean) { if(user) { p.edit().putInt("joystick_sensitivity",v).apply();joyText(v) } }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
        content.addView(activity.label("App shortcuts require the updated, paired TV listener. Shaka (thumb + pinky) = YouTube; three fingers = Netflix; long fist = Stremio. Hold deliberately; short fist stays neutral. Android can block background launches."))
        for(app in AirApp.entries) content.addView(PhoneTheme.switch(activity).apply {
            text="${app.name.lowercase().replaceFirstChar { it.uppercase() }} gesture";isChecked=p.getBoolean("app_${app.name}",true)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean("app_${app.name}",v).apply() }
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Try GPU · automatic CPU fallback (benchmark on this phone first)";isChecked=p.getBoolean("gpu",false)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean("gpu",v).apply() }
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Benchmark two hands instead of four · full household scans retained";isChecked=p.getInt("model_hands",4)==2
            setOnCheckedChangeListener { _,v -> p.edit().putInt("model_hands",if(v) 2 else 4).apply() }
        })
        button("COMPARE CPU / GPU · CALIBRATION ONLY (~104 SECONDS)") {
            AirBenchmark.start(android.os.SystemClock.elapsedRealtime())
            Toast.makeText(activity,"Hold palm to claim, then try gestures. Keep this panel open. No commands sent during comparison.",Toast.LENGTH_LONG).show()
        }
        button("STOP / INSPECT BENCHMARK") {
            AirBenchmark.stop()
            val text=AirBenchmark.summary()
            android.app.AlertDialog.Builder(activity).setTitle("Actual phone timings").setMessage(text)
                .setPositiveButton("Copy") { _,_ -> activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Mirror benchmark",text)) }.setNegativeButton("Close",null).show()
        }
        val dwell=activity.label("")
        fun holdText(ms:Long) { dwell.text="Pointing Hold Duration: $ms ms" }
        holdText(p.getLong("point_hold",400));content.addView(dwell)
        content.addView(SeekBar(activity).apply {
            max=19;progress=((p.getLong("point_hold",400)-250)/50).toInt().coerceIn(0,19)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) { if(user) { val ms=250L+50*value;p.edit().putLong("point_hold",ms).apply();holdText(ms) } }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="640 px inference detail · off = 480 px benchmark";isChecked=p.getInt("input_size",640)==640
            setOnCheckedChangeListener { _,v -> p.edit().putInt("input_size",if(v) 640 else 480).apply() }
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Compatibility input · direct RGB Bitmap fallback";isChecked=p.getBoolean("bitmap_input",false)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean("bitmap_input",v).apply() }
        })
        content.addView(PhoneTheme.switch(activity).apply {
            text="Low-power Clock display · dim to 8%";isChecked=p.getBoolean("dim_clock",false)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean("dim_clock",v).apply() }
        })
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
    fun stop() { handler.removeCallbacks(refresh);AirBenchmark.stop();AirRemote.calibration=false }
}
