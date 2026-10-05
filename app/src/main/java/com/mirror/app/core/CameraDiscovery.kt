package com.mirror.app.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

@Suppress("DEPRECATION")
class CameraDiscovery(context: Context, private val found: (String, String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("Mirror-discovery").apply { setReferenceCounted(false) }
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var active = false
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    fun start() {
        if (active) return
        active = true; lock.acquire()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { stop() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (active && info.serviceType.startsWith("_mirrorcam._tcp")) { queue.add(info); resolveNext() }
            } }
        }
        listener = l
        try { nsd.discoverServices("_mirrorcam._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
        catch (_: Exception) { stop() }
    }
    private fun resolveNext() {
        if (!active || resolving || queue.isEmpty()) return
        resolving = true
        val info = queue.removeFirst()
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if (active) service.host?.hostAddress?.let { host ->
                    runCatching { StreamAddress.forHost(host, service.port) }.getOrNull()?.let { found(service.serviceName, it) }
                }
                resolveNext()
            } }
        }
        try { nsd.resolveService(info, resolver) } catch (_: Exception) { resolving = false; resolveNext() }
    }
    fun stop() {
        active = false; queue.clear()
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        if (lock.isHeld) lock.release()
    }
}
