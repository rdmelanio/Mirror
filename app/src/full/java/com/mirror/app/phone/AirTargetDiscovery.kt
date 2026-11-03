package com.mirror.app.phone

import android.content.Context
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/** Recovery hints only: Remote v2 must still authenticate the stored certificate before accepting any address. */
@Suppress("DEPRECATION")
internal class AirTargetDiscovery(context: Context, private val found: (String)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val lock=context.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("Mirror-air-target").apply { setReferenceCounted(false) }
    private var listener:NsdManager.DiscoveryListener?=null
    private var lastScan=-60_000L
    private val queue=java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    fun scan() { main.post {
        val now=android.os.SystemClock.elapsedRealtime()
        if(listener!=null || now-lastScan<60_000) return@post
        lastScan=now
        val l=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String) {}
            override fun onDiscoveryStopped(type:String) {}
            override fun onStartDiscoveryFailed(type:String,code:Int) { stop() }
            override fun onStopDiscoveryFailed(type:String,code:Int) {}
            override fun onServiceLost(info:NsdServiceInfo) {}
            override fun onServiceFound(info:NsdServiceInfo) { main.post { if(listener!=null && queue.size<8) { queue.add(info);resolve() } } }
        }
        listener=l
        runCatching { lock.acquire();nsd.discoverServices("_androidtvremote2._tcp.",NsdManager.PROTOCOL_DNS_SD,l) }.onFailure { stop() }
        main.postDelayed({ stop() },12000)
    } }
    private fun resolve() {
        if(listener==null || resolving || queue.isEmpty()) return
        resolving=true
        runCatching { nsd.resolveService(queue.removeFirst(),object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,code:Int) { main.post { resolving=false;resolve() } }
            override fun onServiceResolved(info:NsdServiceInfo) { main.post {
                resolving=false
                if(listener!=null && info.port==6466) runCatching { TvTarget.host(info.host?.hostAddress.orEmpty()) }.getOrNull()?.let(found)
                resolve()
            } }
        }) }.onFailure { resolving=false;resolve() }
    }
    fun stop() { main.post {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } };listener=null;queue.clear();resolving=false
        if(lock.isHeld) lock.release()
    } }
}
