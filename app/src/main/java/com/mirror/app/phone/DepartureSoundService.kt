package com.mirror.app.phone

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import com.mirror.app.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** A bounded audio foreground service, independent of the camera service. Only a timed wake lock during audible playback. */
class DepartureSoundService : Service() {
    private var player: MediaPlayer? = null
    private var loopTrack: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var playbackWake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = DepartureAlerts.active(this)
        if (active == null || active.token != intent?.getStringExtra("token")) { stopSelf(); return START_NOT_STICKY }
        val notification = notification(this, active)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification)
        if (token == active.token) return START_NOT_STICKY
        token = active.token; releaseAudio(); handler.removeCallbacksAndMessages(null)
        val settings = ClockSettings.load(this)
        val limit = if (active.kind == DeparturePlan.Kind.WARNING) settings.warningSeconds * 1000L else 5000L
        val duration = minOf(limit, active.expires - System.currentTimeMillis()).coerceAtLeast(1)
        // Keep the stop timer running with the screen off; never hold it for the visual alert.
        playbackWake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mirror:DepartureAudio").apply {
            setReferenceCounted(false); acquire(duration + 2000)
        }
        handler.postDelayed({ finishAudio() }, duration)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) finishAudio() }
            .build()
        focus = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DepartureAlerts.prefs(this).edit().putString("audioStatus", "Audio focus unavailable · visual alert remains active").apply()
            finishAudio(); return START_NOT_STICKY
        }
        val uri = if (active.kind == DeparturePlan.Kind.CAUTION) settings.cautionSound else settings.warningSound
        if (active.kind == DeparturePlan.Kind.WARNING && uri.isBlank()) {
            try {
                val pcm = resources.openRawResource(R.raw.airbus_master_warning).use { it.readBytes() }
                val track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
                loopTrack = track
                check(track.state != AudioTrack.STATE_UNINITIALIZED) { "Warning audio output unavailable" }
                check(track.write(pcm, 0, pcm.size) == pcm.size) { "Warning buffer incomplete" }
                check(track.state == AudioTrack.STATE_INITIALIZED) { "Warning audio output unavailable" }
                check(track.setLoopPoints(0, pcm.size / 2, -1) == AudioTrack.SUCCESS) { "Warning loop unavailable" }
                track.play()
            } catch (_: Exception) {
                DepartureAlerts.prefs(this).edit().putString("audioStatus", "Warning could not play · check alarm output and test again").apply()
                finishAudio()
            }
            return START_NOT_STICKY
        }
        val media = MediaPlayer(); player = media
        media.setAudioAttributes(attributes)
        media.isLooping = active.kind == DeparturePlan.Kind.WARNING
        media.setOnCompletionListener { if (active.kind == DeparturePlan.Kind.CAUTION) finishAudio() }
        media.setOnErrorListener { _, _, _ ->
            DepartureAlerts.prefs(this).edit().putString("audioStatus", "Sound could not play · test or reselect your audio clip").apply()
            finishAudio(); true
        }
        try {
            if (uri.isNotBlank()) media.setDataSource(this, android.net.Uri.parse(uri))
            else media.setDataSource(toneFile(active.kind).absolutePath)
            media.setOnPreparedListener { if (player === it && DepartureAlerts.active(this)?.token == token) it.start() }
            media.prepareAsync()
        } catch (_: Exception) {
            DepartureAlerts.prefs(this).edit().putString("audioStatus", "Sound could not open · test or reselect your audio clip").apply(); finishAudio()
        }
        return START_NOT_STICKY
    }
    private fun finishAudio() {
        handler.removeCallbacksAndMessages(null); releaseAudio()
        // Detach the visual notification. It remains until acknowledgement/ten-minute expiry.
        stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
    }
    private fun releaseAudio() {
        player?.release(); player = null
        loopTrack?.let { track -> runCatching { if (track.state == AudioTrack.STATE_INITIALIZED) track.stop() }; track.release() }; loopTrack = null
        playbackWake?.let { if (it.isHeld) it.release() }; playbackWake = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); releaseAudio(); super.onDestroy() }
    /** Original synthesized tones; not Airbus recordings. WAV is cached and generated once per tone. */
    private fun toneFile(kind: DeparturePlan.Kind): File {
        val file = File(cacheDir, "departure-${kind.name.lowercase()}-v1.wav")
        if (file.isFile) return file
        val rate = 22050; val samples = rate
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        for (n in 0 until samples) {
            val t = n.toDouble() / rate
            val value = if (kind == DeparturePlan.Kind.CAUTION) {
                val envelope = (1 - exp(-t * 150)) * exp(-t * 6)
                envelope * (sin(2 * PI * 880 * t) * 0.65 + sin(2 * PI * 1760 * t) * 0.35)
            } else {
                val pulse = t % 0.5
                val envelope = if (pulse < 0.35) minOf(pulse / 0.01, (0.35 - pulse) / 0.01, 1.0).coerceAtLeast(0.0) else 0.0
                envelope * (sin(2 * PI * 800 * t) * 0.6 + sin(2 * PI * 1600 * t) * 0.4)
            }
            data.putShort((value * 24000).toInt().coerceIn(-32768, 32767).toShort())
        }
        file.writeBytes(data.array()); return file
    }
    companion object {
        const val CHANNEL = "mirror-departure-v1"
        const val NOTIFICATION = 17070
        fun createChannel(c: Context) {
            val manager = c.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Departure alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calendar departure caution and warning alarms"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(null, null); enableVibration(false)
            })
        }
        fun notification(c: Context, active: DepartureAlerts.Active): Notification {
            createChannel(c)
            val open = PendingIntent.getActivity(c, 17071, Intent(c, ClockActivity::class.java)
                .putExtra("departure", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getBroadcast(c, 17072, Intent(c, DepartureReceiver::class.java).setAction(DepartureAlerts.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mirror).setContentTitle("Mirror alarm")
                .setContentText("Open Mirror to view or stop the alert").build()
            return Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mirror)
                .setContentTitle(if (active.kind == DeparturePlan.Kind.WARNING) "Master warning" else "Master caution")
                .setContentText("Departure reminder · tap to view").setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(open)
                .setFullScreenIntent(open, true).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        }
        fun notifyAlert(c: Context) {
            val active = DepartureAlerts.active(c) ?: return
            if (DepartureAlerts.notificationsAllowed(c)) c.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(c, active))
        }
    }
}
