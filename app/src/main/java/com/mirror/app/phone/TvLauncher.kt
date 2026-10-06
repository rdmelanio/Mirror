package com.mirror.app.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent from the camera service. No foreground service, persistent socket, job or wake lock. */
internal object TvLauncher {
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    val listeners = mutableSetOf<() -> Unit>() // main thread only
    var status = "Pair Mirror with your TV once."; private set
    val running get() = busy.get()
    fun prefs(context: Context) = context.getSharedPreferences("mirror_tv_launch", Context.MODE_PRIVATE)
    fun paired(context: Context) = prefs(context).getString("guid", "").orEmpty().isNotEmpty()
    private fun announce(text: String) { main.post { status = text; listeners.toList().forEach { it() } } }
    private fun operation(context: Context, work: (Context) -> String) {
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext; announce("Connecting to TV…")
        worker.execute {
            val result = runCatching { work(app) }.getOrElse { error ->
                when (error) {
                    is SocketTimeoutException -> "TV did not respond. Check Wi-Fi and wireless debugging."
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "Check TV launch settings."
                    else -> "TV connection failed. Check the current port, pairing code and Wi-Fi."
                }
            }
            main.post { busy.set(false); status = result; listeners.toList().forEach { it() } }
        }
    }
    private fun <T> socket(endpoint: TvDiscovery.Endpoint, work: (Socket) -> T): T {
        val socket = Socket()
        val limit = deadlines.schedule({ runCatching { socket.close() } }, 20, TimeUnit.SECONDS)
        try {
            socket.soTimeout = 12000; socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 4000)
            return work(socket)
        } finally { limit.cancel(false); runCatching { socket.close() } }
    }
    fun pair(context: Context, host: String, pairingPort: Int, code: String, connectionPort: Int) = operation(context) { app ->
        val identity = TvCredentials.load(app, create = true)
        val guid = socket(TvDiscovery.Endpoint(TvTarget.host(host), pairingPort)) { TvAdbWire.pair(it, identity.key, identity.certificate, code) }
        prefs(app).edit().putString("host", host).putInt("port", connectionPort).putString("guid", guid).apply()
        ClockSettings.load(app).copy(tvIcon = true).save(app)
        "Paired · tap the clock's mirror icon to launch TV."
    }
    fun launch(context: Context) = operation(context) { app ->
        check(paired(app)) { "Pair Mirror in Settings → TV launch first." }
        val identity = TvCredentials.load(app); val p = prefs(app)
        announce("Finding paired TV…")
        val discovered = runCatching { TvDiscovery.find(app, p.getString("guid", "").orEmpty()) }.getOrNull()
        val endpoint = discovered ?: TvDiscovery.Endpoint(TvTarget.host(p.getString("host", "").orEmpty()), p.getInt("port", 0))
        check(endpoint.port in 1..65535) { "Enter the TV's connection port in Settings → TV launch." }
        announce("Waking TV and launching Mirror…")
        val result = socket(endpoint) { TvAdbWire.launch(it, identity.key, identity.certificate) }
        if (discovered != null) p.edit().putString("host", endpoint.host).putInt("port", endpoint.port).apply()
        result
    }
    fun forget(context: Context) {
        if (running) return
        prefs(context).edit().clear().apply(); TvCredentials.forget(context)
        announce("TV forgotten. Pair again to reconnect.")
    }
}
