package com.mirror.app.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A short discovery window owned by a user action; never scans in the background. */
@Suppress("DEPRECATION")
internal object TvDiscovery {
    data class Endpoint(val host: String, val port: Int)
    fun find(context: Context, guid: String): Endpoint? {
        val manager = context.getSystemService(NsdManager::class.java)
        val latch = CountDownLatch(1); val closed = AtomicBoolean(false); val resolving = AtomicBoolean(false)
        val answer = AtomicReference<Endpoint?>(null)
        val lock = context.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("Mirror TV discovery").apply { setReferenceCounted(false) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (closed.get() || !TvTarget.matches(service.serviceName, guid) || !resolving.compareAndSet(false, true)) return
                runCatching { manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolving.set(false) }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (closed.get()) return
                        val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                        val host = addresses.mapNotNull { it.hostAddress?.let { raw -> runCatching { TvTarget.host(raw) }.getOrNull() } }.firstOrNull()
                        if (host != null && info.port in 1..65535) { answer.set(Endpoint(host, info.port)); latch.countDown() }
                        else resolving.set(false)
                    }
                }) }.onFailure { resolving.set(false) }
            }
        }
        var started = false
        try {
            lock.acquire(); manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener); started = true
            latch.await(6, TimeUnit.SECONDS)
            return answer.get()
        } finally {
            closed.set(true)
            if (started) runCatching { manager.stopServiceDiscovery(listener) }
            if (lock.isHeld) lock.release()
        }
    }
}
