package com.mirror.app.phone.roster

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import com.mirror.app.R
import kotlin.math.*

class RosterSoundService : Service() {
    companion object { const val STOP = "roster-stop" }
    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var key: String? = null
    private val ring = object : Runnable {
        override fun run() {
            if (audio != null) { audio?.stop(); audio?.reloadStaticData(); audio?.play() } else ringtone?.play()
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
            handler.postDelayed(this, 3000)
        }
    }
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!RosterStore.phone(this)) { stopSelf(); return START_NOT_STICKY }
        key = i?.getStringExtra("key")
        val plan = key?.let { RosterAlarms.find(this, it) } ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("roster-alarm", "Roster alarms", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        val stop = PendingIntent.getService(this, 700, Intent(this, RosterSoundService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(640, Notification.Builder(this, "roster-alarm").setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle("MASTER CAUTION · ${plan.alarm.label}").setContentText("Tap to acknowledge")
            .setContentIntent(RosterAlarms.screen(this, plan)).setFullScreenIntent(RosterAlarms.screen(this, plan), true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Acknowledge", stop).build()).build())
        handler.removeCallbacksAndMessages(null); audio?.release(); ringtone?.stop()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (RosterStore.prefs(this).getBoolean("systemSound", false)) {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply { audioAttributes = attributes }
        } else {
            // Original decaying sine chime, synthesized here; no bundled recording.
            val rate = 22050; val samples = ShortArray(rate)
            samples.indices.forEach { n -> val t = n.toDouble() / rate; val envelope = minOf(t / 0.015, 1.0) * exp(-t * 5)
                samples[n] = (sin(2 * PI * 880 * t) * envelope * 18000).toInt().toShort() }
            audio = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build().apply { write(samples, 0, samples.size) }
        }
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        handler.post(ring)
        handler.postDelayed({
            RosterNotices.post(this, 641, "Missed roster alarm · ${plan.alarm.label}", "Report ${plan.duty.reportLocal?.toLocalTime()}")
            sendBroadcast(Intent("com.mirror.app.ROSTER_SOUND_STOPPED").setPackage(packageName)); stopSelf()
        }, 300_000)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); audio?.stop(); audio?.release(); ringtone?.stop(); vibrator?.cancel()
        getSystemService(NotificationManager::class.java).cancel(640); super.onDestroy()
    }
}
