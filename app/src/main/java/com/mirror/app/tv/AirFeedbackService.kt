package com.mirror.app.tv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import com.mirror.app.R
import com.mirror.app.core.*
import com.mirror.app.phone.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Long-polls authenticated events using the existing Mirror viewer pairing. No camera/stream is opened. */
class AirFeedbackService : Service() {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var overlay: AirFeedbackOverlay
    @Volatile private var socket: Socket?=null
    @Volatile private var stopped=false
    private var started=false
    private val visuals=AirFeedbackView()
    private var stateAfter=-1L
    private var protocol=1
    private val render=object:Runnable { override fun run() {
        if(!stopped) { overlay.render(visuals.current(SystemClock.elapsedRealtime()));main.postDelayed(this,250) }
    } }
    private var after=-1L
    private var epoch=""
    private var source=""
    private var feedbackAddress:String?=null
    private var configuredSource=""
    private var failures=0
    private var discovery: CameraDiscovery?=null
    private var lastDiscovery=-60_000L
    override fun onBind(intent: Intent?)=null
    override fun onCreate() { super.onCreate();overlay=AirFeedbackOverlay(this);main.post(render) }
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if(!mirrorPreferences().getBoolean("airFeedback",false)) { stopSelf();return START_NOT_STICKY }
        try {
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("air_feedback","Air Remote listener",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,8081,Intent(this,TvActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=Notification.Builder(this,"air_feedback").setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror Air Remote")
                .setContentText("Background feedback listener · no permanent overlay").setContentIntent(open).setOngoing(true).build()
            if(Build.VERSION.SDK_INT>=29) startForeground(8081,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(8081,notification)
        } catch (_:Exception) { status="Android blocked background feedback · open Mirror on TV";stopSelf();return START_NOT_STICKY }
        if(!started) { started=true;schedule(0) }
        return START_STICKY
    }
    private fun schedule(delay: Long) { if(!stopped) runCatching { worker.schedule({ poll() },delay,TimeUnit.MILLISECONDS) } }
    private fun poll() {
        var delay=0L
        try {
            val prefs=mirrorPreferences();check(prefs.getBoolean("airFeedback",false))
            val settings=TvSettings(prefs)
            check(settings.cameraId.isNotEmpty()) { "Pair this TV with the Mirror phone first" }
            val token=ViewerPairing(prefs).token(settings.cameraId) ?: error("Mirror phone pairing is missing/revoked")
            val configured="${settings.cameraId}:${settings.url}"
            if(configuredSource!=configured) { configuredSource=configured;feedbackAddress=null }
            val uri=URI(feedbackAddress ?: settings.url)
            val host=TvTarget.host(uri.host.orEmpty())
            val selected="$host:${settings.cameraId}"
            if(source!=selected) { source=selected;after=-1;stateAfter=-1;protocol=1;epoch="";main.post { visuals.disconnect();overlay.clear() } }
            val nonce=AirFeedbackAuth.nonce();val key=AirFeedbackAuth.key(token)
            val signed=if(protocol>=2) AirFeedbackAuth.requestV2(nonce,after,stateAfter) else AirFeedbackAuth.request(nonce,after)
            val request=JSONObject().put("nonce",nonce).put("after",after).put("mac",AirFeedbackAuth.sign(key,signed))
            if(protocol>=2) request.put("version",2).put("stateAfter",stateAfter)
            val begin=SystemClock.elapsedRealtime()
            val value=Socket().use { s ->
                socket=s;s.soTimeout=12000;s.connect(InetSocketAddress(host,8081),2500)
                s.getOutputStream().write((request.toString()+"\n").toByteArray(Charsets.UTF_8))
                JSONObject(AirFeedbackServer.readLine(s.getInputStream()))
            }
            socket=null
            val body=value.getString("body")
            check(AirFeedbackAuth.valid(key,"$nonce:$body",value.getString("mac"))) { "Feedback authentication failed" }
            val message=JSONObject(body);val nextEpoch=message.getString("epoch");val sequence=message.getLong("seq")
            val transit=(SystemClock.elapsedRealtime()-begin-message.getLong("waitMs")).coerceAtLeast(0)
            val fresh=transit<=2000
            epoch=nextEpoch;after=sequence;failures=0
            protocol=message.optInt("version",1)
            val items=message.optJSONArray("events")
            if(items!=null) for(i in 0 until items.length()) after=maxOf(after,items.getJSONObject(i).getLong("id"))
            stateAfter=message.optJSONObject("snapshot")?.optLong("revision",-1) ?: -1
            status=when {
                !Settings.canDrawOverlays(this) -> "Listening · in-app only (overlay permission unavailable)"
                AirFeedbackOverlay.windowRejected -> "Listening · in-app only (Android rejected overlay window)"
                else -> "Listening · overlay permission granted; apps may hide it"
            }
            main.post { discovery?.stop();discovery=null;if(!stopped) visuals.accept(message,SystemClock.elapsedRealtime(),fresh,transit) }
        } catch (e:Exception) {
            socket=null;failures++;after=-1;stateAfter=-1;protocol=1;epoch="";delay=(failures*2000L).coerceAtMost(30_000)
            status="Feedback waiting: ${e.message ?: "phone offline"}"
            main.post { visuals.disconnect();overlay.clear();if(!stopped) discover() }
        } finally { schedule(delay) }
    }
    private fun discover() {
        val now=SystemClock.elapsedRealtime()
        if(discovery!=null || now-lastDiscovery<60_000) return
        lastDiscovery=now
        val id=TvSettings(mirrorPreferences()).cameraId
        discovery=CameraDiscovery(this) { _,address ->
            if(!stopped) worker.execute { runCatching {
                val found=ViewerPairing(mirrorPreferences()).hello(address)
                // A feedback discovery must never change the saved video target or expose its bearer token.
                if(found?.id==id && TvSettings(mirrorPreferences()).cameraId==id) feedbackAddress=address
            } }
        }.also { it.start() }
        main.postDelayed({ discovery?.stop();discovery=null },12000)
    }
    override fun onDestroy() {
        stopped=true;if(!mirrorPreferences().getBoolean("airFeedback",false)) status="Feedback disabled";runCatching { socket?.close() };worker.shutdownNow();main.removeCallbacksAndMessages(null)
        discovery?.stop();overlay.clear();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        @Volatile var status="Background feedback not started"
        fun resume(context: Context) {
            if(context.mirrorPreferences().getBoolean("airFeedback",false)) runCatching { context.startForegroundService(Intent(context,AirFeedbackService::class.java)) }
                .onFailure { status="Background start restricted · open Mirror once on TV" }
        }
    }
}

class AirFeedbackBoot : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED) && context.mirrorPreferences().getString("role","")=="tv") AirFeedbackService.resume(context)
    }
}
