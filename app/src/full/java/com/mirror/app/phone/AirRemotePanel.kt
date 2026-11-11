package com.mirror.app.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.*
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.mirror.app.core.*

/** Basic controls explain daily use; technical comparisons are opt-in and collapsed. */
internal class AirRemotePanel(private val activity:ComponentActivity,private val content:LinearLayout) {
    private val handler=Handler(Looper.getMainLooper())
    private val p=AirRemoteTv.prefs(activity)
    private val legacyNotice=activity.label("Legacy Gesture Mode is active and overrides Navigation Mode. Turn it off in Advanced to use Air Joystick or Directional Pointing.").apply { visibility=if(p.getBoolean("legacy",false)) android.view.View.VISIBLE else android.view.View.GONE }
    private val health=activity.label("")
    private val detail=activity.label("")
    private val advanced=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;visibility=android.view.View.GONE }
    private val joystickControls=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val preciseJoystick=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private var testCommand:android.view.View?=null
    private val refresh=object:Runnable { override fun run() {
        health.text=AirRemote.healthSummary()
        testCommand?.isEnabled=AirRemoteTv.connected && !AirRemote.calibration
        if(advanced.visibility==android.view.View.VISIBLE) detail.text=AirRemote.diagnostics()
        handler.postDelayed(this,1000)
    } }
    private fun label(parent:LinearLayout,text:String)=parent.addView(activity.label(text))
    private fun button(parent:LinearLayout,title:String,action:()->Unit):android.view.View=activity.action(title) {
        runCatching(action).onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() }
    }.also { parent.addView(it) }
    private fun toggle(parent:LinearLayout,title:String,key:String,default:Boolean,description:String,change:((Boolean)->Unit)?=null) {
        label(parent,description)
        parent.addView(PhoneTheme.switch(activity).apply {
            fun update(v:Boolean) { text="$title: ${if(v) "On" else "Off"} · default ${if(default) "On" else "Off"}" }
            isChecked=p.getBoolean(key,default);update(isChecked)
            setOnCheckedChangeListener { _,v -> p.edit().putBoolean(key,v).apply();update(v);change?.invoke(v) }
        })
    }
    private fun choices(parent:LinearLayout,title:String,key:String,values:List<Any>,names:List<String>,default:Any,description:String,change:((Int)->Unit)?=null) {
        label(parent,"$title · factory default: ${names[values.indexOf(default).coerceAtLeast(0)]}\n$description")
        val current=p.all[key] ?: default
        parent.addView(Spinner(activity).apply {
            adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,names.map { "Your selection: $it" })
            setSelection(values.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                    val value=values[position]
                    if((p.all[key] ?: default)!=value) {
                        AirRemoteTv.discardPending()
                        val e=p.edit();when(value) { is String -> e.putString(key,value);is Int -> e.putInt(key,value);is Long -> e.putLong(key,value) };e.apply()
                    }
                    change?.invoke(position)
                }
                override fun onNothingSelected(parent:AdapterView<*>?) {}
            }
        })
    }
    private fun slider(parent:LinearLayout,title:String,key:String,default:Int,min:Int,max:Int,step:Int,description:String,longValue:Boolean=false,suffix:String="") {
        val caption=activity.label("");parent.addView(caption);label(parent,description)
        fun selected()=if(longValue) p.getLong(key,default.toLong()).toInt() else p.getInt(key,default)
        fun show(v:Int) { caption.text="$title: $v$suffix · factory default $default$suffix" };show(selected())
        parent.addView(SeekBar(activity).apply {
            this.max=(max-min)/step;progress=((selected()-min)/step).coerceIn(0,this.max)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,v:Int,user:Boolean) { if(user) { val value=min+v*step;val e=p.edit();if(longValue) e.putLong(key,value.toLong()) else e.putInt(key,value);e.apply();show(value) } }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
            })
        })
    }
    private fun pairing() {
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val address=EditText(activity).apply { hint="TV IPv4 address";setSingleLine();setText(p.getString("host",TvLauncher.prefs(activity).getString("host",""))) }
        val code=EditText(activity).apply { hint="Six-character code shown on TV";setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(address);box.addView(code)
        label(box,"Already paired? Use Reconnect; no new pairing code is needed. Pairing here is separate from Mirror camera pairing and ADB TV launch.")
        button(box,"RECONNECT SAVED TV") { AirRemoteTv.retry(activity,address.text.toString()) }
        button(box,"PAIR ONCE · SHOW TV CODE") { AirRemoteTv.pair(activity,address.text.toString()) }
        button(box,"SUBMIT CODE") { AirRemoteTv.code(code.text.toString());code.setText("") }
        android.app.AlertDialog.Builder(activity).setTitle("Pair / Connect TV").setView(box).setNegativeButton("Close",null).show()
    }
    init {
        AirRemoteTv.init(activity);AirSettings.applyMigration(activity)
        label(content,"AIR REMOTE · BASIC");content.addView(legacyNotice)
        label(content,AirSettingPolicy.BASIC_GUIDE)
        val notice=p.getString("migration_notice","").orEmpty()
        if(notice.isNotEmpty()) { label(content,notice);p.edit().remove("migration_notice").apply() }
        label(content,if(AirSettings.onePlus()) "Recommended OnePlus 6T front-camera profile. Camera choice and Clock settings are retained." else "Recommended safe profile. Use your own device measurements before enabling GPU.")
        toggle(content,"Enable Air Remote","enabled",false,"Camera-based control of your paired TV. Applies immediately. Keep Mirror Clock available; after reboot or force-stop Android may require reopening it.") { enabled ->
            if(enabled && activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
                p.edit().putBoolean("enabled",false).apply();activity.requestPermissions(arrayOf(Manifest.permission.CAMERA),8090)
                Toast.makeText(activity,"Allow the camera, then enable Air Remote again",Toast.LENGTH_LONG).show();activity.recreate()
            } else if(enabled) {
                val settings=activity.mirrorPreferences()
                runCatching { activity.startForegroundService(Intent(activity,CameraService::class.java).putExtra("front",settings.getBoolean("phoneFront",false)).putExtra("fullHd",settings.getBoolean("phoneFullHd",false))) }
                    .onFailure { p.edit().putBoolean("enabled",false).apply();Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() }
            } else { AirBenchmark.stop();AirRemoteTv.stop() }
        }
        choices(content,"Navigation Mode","navigation",listOf("joystick","pointing"),listOf("Air Joystick — recommended","Directional Pointing — alternative"),"joystick",
            "Joystick: keep one index extended and move the whole hand. Pointing: aim the finger directly UP/DOWN/LEFT/RIGHT. Applies on the next observation; pending directions clear and the hand must relax before a new neutral center.") { i -> joystickControls.visibility=if(i==0) android.view.View.VISIBLE else android.view.View.GONE;preciseJoystick.visibility=joystickControls.visibility }
        choices(content,"Distance Optimization","hand_lock",listOf("auto","wide","distance"),listOf("Automatic — recommended","Wide View — troubleshooting","Long Distance — 8–12 feet"),"auto",
            "Automatic discovers broadly, follows your hand and expands recovery. Wide View always uses the full scene. Long Distance favors hand detail with wider movement margins; it may cost more processing. Immediate; no camera restart. Cropping cannot restore missing optical detail.")
        content.addView(joystickControls)
        label(joystickControls,"Joystick sensitivity · higher sensitivity needs less movement. Factory default: Normal. Immediate; no restart.")
        joystickControls.addView(Spinner(activity).apply {
            adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("Low — larger movement","Normal — recommended","High — smaller movement"))
            val initial=AirSettingPolicy.preset(p.getInt("joystick_sensitivity",50));setSelection(initial)
            var first=true
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { if(first) first=false else p.edit().putInt("joystick_sensitivity",AirSettingPolicy.presetValues[position]).apply() }
                override fun onNothingSelected(parent:AdapterView<*>?) {}
            }
        })
        choices(content,"Session Inactivity","session_timeout",listOf(30_000L,60_000L,120_000L,300_000L),listOf("30 seconds","1 minute","2 minutes","5 minutes"),60_000L,
            "Your saved selection is retained on update. Time since deliberate interaction; tracking loss pauses commands, not the session. Applies immediately.")
        label(content,"Favorite App Gestures · individually enabled by default. Needs the updated, phone-paired TV feedback listener. Android may block background app launches; intent submission is not execution confirmation. Immediate; not used in Legacy Mode.")
        for((app,name) in listOf(AirApp.YOUTUBE to "Shaka (Y) → YouTube · hold 1 second",AirApp.NETFLIX to "Three raised fingers → Netflix · hold 1.1 seconds",AirApp.STREMIO to "Long closed fist → Stremio · hold 1.45 seconds")) toggle(content,name,"app_${app.name}",true,"Short fist is neutral. Relax between launches.")
        label(content,"Connection and Health");content.addView(health)
        button(content,"PAIR / CONNECT TV") { pairing() }
        testCommand=button(content,"TEST D-PAD RIGHT") { Toast.makeText(activity,AirRemoteTv.send(AirGestures.Command.RIGHT),Toast.LENGTH_SHORT).show() }
        button(content,"COPY DIAGNOSTICS · RECENT MINUTE") { activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Mirror sanitized diagnostics",AirRemote.copyDiagnostics()));Toast.makeText(activity,"Sanitized diagnostics copied",Toast.LENGTH_SHORT).show() }
        button(content,"RESTORE RECOMMENDED AIR REMOTE SETTINGS") {
            android.app.AlertDialog.Builder(activity).setTitle("Restore Air Remote recommendations?")
                .setMessage("Sets Joystick, Automatic distance/input, Normal sensitivity, CPU, ${AirSettings.handDefault()} hands, 640 px, 65% confidence, 400 ms pointing hold, one-minute timeout, app gestures On and Legacy Off. Pairing, camera selection, mirroring, permissions and Clock/display stay unchanged.")
                .setPositiveButton("Restore") { _,_ -> AirSettings.restore(activity);activity.recreate() }.setNegativeButton("Cancel",null).show()
        }
        val disclosure=button(content,"ADVANCED · SHOW") {} as TextView
        disclosure.setOnClickListener { val show=advanced.visibility!=android.view.View.VISIBLE;advanced.visibility=if(show) android.view.View.VISIBLE else android.view.View.GONE;disclosure.text=if(show) "ADVANCED · HIDE" else "ADVANCED · SHOW" }
        content.addView(advanced)
        slider(advanced,"Gesture acceptance threshold","confidence",65,50,95,5,"Compatibility: model classification threshold. Pose Guided: required consistency of validated palm geometry across claim frames (at least 65%). Geometry is not a probability. One-second claim and ownership checks still apply.",suffix="%")
        slider(advanced,"Directional Pointing Hold","point_hold",400,250,1200,50,"Applies to Directional Pointing; Home retains a longer deliberate hold. Immediate; no camera restart.",longValue=true,suffix=" ms")
        advanced.addView(preciseJoystick)
        slider(preciseJoystick,"Precise Joystick Sensitivity","joystick_sensitivity",50,0,100,1,"Joystick only: higher values need smaller movement. Immediate; use Basic presets for normal use.")
        choices(advanced,"Vision Engine","vision_engine",listOf("pose","compatibility"),listOf("Pose guided — recommended","Gesture Recognizer — compatibility"),"pose","Pose guidance helps locate distant hands. Compatibility is used automatically if the new engine cannot run.")
        choices(advanced,"Maximum Persons","pose_persons",listOf(1,2),listOf("One person","Two persons — household"),2,"Reduce to one if body detection is too expensive. Hand-only fallback remains available.")
        choices(advanced,"Image Input","input_mode",listOf("auto","bitmap","direct"),listOf("Automatic — recommended","Bitmap — compatibility","Direct RGB — diagnostic"),"auto","Automatic uses validated exact-size aligned RGB; any rejected direct route switches to Bitmap once. Direct diagnostic also falls back safely. Next frame; no camera restart.")
        choices(advanced,"Inference Detail","input_size",listOf(480,640),listOf("480 px — compare speed/detail","640 px — recommended initial detail"),640,"Larger input may preserve distant fingers but cost time. Severe thermal pressure can reduce detail. Next frame; no camera restart. No OnePlus FPS gain is assumed.")
        toggle(advanced,"GPU experiment","gpu",false,"Optional and unmeasured on your OnePlus 6T. Reinitializes the model on its worker; CPU fallback on failure. Brief recognition pause, no camera restart or re-pairing.")
        choices(advanced,"Compatibility engine: maximum hands","model_hands",listOf(2,4),listOf("Two hands — efficient household profile","Four hands — wider household profile"),AirSettings.handDefault(),"Factory recommendation: two on OnePlus 6T, four on other devices. Model recreates safely on the worker; temporary recognition pause, no camera restart. Wide takeover scans remain.")
        toggle(advanced,"Legacy Gesture Mode","legacy",false,"Overrides normal navigation with the previous swipe/fist mappings. App shortcuts are suspended. Gesture-engine reset on next observation; pairing and camera remain.") { value -> legacyNotice.visibility=if(value) android.view.View.VISIBLE else android.view.View.GONE }
        slider(advanced,"Legacy Swipe Sensitivity","sensitivity",50,0,100,1,"Legacy only: higher needs less swipe distance. Immediate; no camera restart.")
        toggle(advanced,"Mirror horizontal controls","mirror",true,"Viewer-facing coordinate correction. Preserve your front-camera preference. Immediate; gesture tracking revalidates before more commands.")
        label(advanced,"Calibration · default Off. Recognizes intended commands without transmitting; entering clears queued keys/app requests. Applies immediately; may require a fresh claim.")
        advanced.addView(PhoneTheme.switch(activity).apply { text="Calibration without TV commands";isChecked=AirRemote.calibration;setOnCheckedChangeListener { _,v -> AirRemote.calibration=v } })
        button(advanced,"COMPARE CPU / GPU · CALIBRATION (~104 SECONDS)") { AirBenchmark.start(android.os.SystemClock.elapsedRealtime());Toast.makeText(activity,"No commands during comparison. Claim and try gestures; keep this panel open.",Toast.LENGTH_LONG).show() }
        button(advanced,"STOP / COPY BENCHMARK") { AirBenchmark.stop();val text=AirBenchmark.summary();android.app.AlertDialog.Builder(activity).setTitle("Actual device timings").setMessage(text).setPositiveButton("Copy") { _,_ -> activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Mirror benchmark",text)) }.setNegativeButton("Close",null).show() }
        if(p.contains("migration_215_backup")) button(advanced,"UNDO UPGRADE ADVANCED TUNING") { android.app.AlertDialog.Builder(activity).setMessage("Restore the saved pre-upgrade input/GPU/hand-count choices? Experimental GPU can still fail and will use CPU fallback. Pairing and Clock are untouched.").setPositiveButton("Restore") { _,_ -> AirSettings.undoUpgrade(activity);activity.recreate() }.setNegativeButton("Cancel",null).show() }
        label(advanced,"CLOCK / DISPLAY · independent of recognition")
        label(advanced,"Detailed local diagnostics · no images, coordinates or credentials are stored. CPU/GPU benchmarking requires the actual phone. TV overlays can be hidden by protected apps.")
        advanced.addView(detail)
    }
    fun start() { handler.removeCallbacks(refresh);refresh.run() }
    fun stop() { handler.removeCallbacks(refresh);AirBenchmark.stop();AirRemote.calibration=false }
}
