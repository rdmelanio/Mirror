package com.mirror.app.phone

import org.json.JSONObject
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Separate two-client status channel; never shares MJPEG workers, frames, or command endpoints. */
internal class AirFeedbackServer(private val security: PairingAuthority, bind: InetAddress,
    private val allowedPeer: (InetAddress) -> Boolean, port: Int = 8081) : AutoCloseable {
    private val server=ServerSocket().apply { reuseAddress=true;bind(java.net.InetSocketAddress(bind,port)) }
    val port get()=server.localPort
    private val events=AirFeedbackEvents()
    private val launches=AirLaunchMailbox()
    fun launchSupported()=peerVersion>=3 && authenticatedAt>=0 && now()-authenticatedAt<15_000 && launches.supported()
    @Volatile private var authenticatedAt=-1L
    @Volatile private var rejectedAt=-1L
    @Volatile private var peerVersion=0
    @Volatile private var overlayPermission:Boolean?=null
    private fun now()=System.nanoTime()/1_000_000
    fun cancelLaunches()=launches.cancel()
    fun launchStatus():String {
        val state=when {
            security.list().isEmpty() -> "TV companion not camera-paired with this phone; pair Mirror viewer on TV"
            rejectedAt>authenticatedAt -> "Unauthenticated TV listener request; check Mirror viewer pairing with this phone"
            authenticatedAt<0 -> "No TV companion contact; enable TV feedback listener, check viewer pairing and Wi-Fi"
            now()-authenticatedAt>15_000 -> "TV companion offline or reconnecting; open Mirror on TV and check listener / Wi-Fi"
            peerVersion<3 -> "Paired feedback listener; negotiating app capability — update TV APK if this persists"
            overlayPermission==false -> "Shortcut listener connected; overlay permission missing — Android may block background launches"
            else -> "Shortcut listener connected; app capability negotiated"
        }
        val last=launches.status
        return state + if(AirApp.entries.any { last.startsWith(it.name) }) "\n$last" else ""
    }
    fun launch(app:AirApp):Boolean=(launchSupported() && launches.submit(app)).also { if(it) events.wake() }
    private val slots=Semaphore(2)
    private val clients=ConcurrentHashMap.newKeySet<Socket>()
    private val workers=Executors.newFixedThreadPool(2)
    @Volatile private var closed=false
    private val nonces=LinkedHashMap<String,Long>()
    init { Thread({
        while(!closed) try {
            val socket=server.accept()
            if(!allowedPeer(socket.inetAddress) || !slots.tryAcquire()) { socket.close();continue }
            clients.add(socket)
            workers.execute { try { socket.use { handle(it) } } catch (_:Exception) {} finally { clients.remove(socket);slots.release() } }
        } catch (_:Exception) { if(!closed) close() }
    },"Mirror-air-feedback").start() }
    fun snapshot(value:JSONObject)=events.snapshot(value)
    fun post(event: String) = events.post(event)
    private fun handle(socket: Socket) {
        socket.soTimeout=2000
        val request=JSONObject(readLine(socket.getInputStream()))
        val nonce=request.getString("nonce");require(nonce.matches(Regex("[0-9a-f]{32}")))
        val after=request.getLong("after");require(after>=-1)
        val version=request.optInt("version",1);require(version in 1..3)
        val v2=version>=2
        val ack=if(version>=3) request.getString("launchAck") else ""
        if(version>=3) require(request.optString("capability")=="apps-v1" && ack.length<=512)
        val revision=if(v2) request.getLong("stateAfter") else null
        val body=if(version>=3) AirFeedbackAuth.requestV3(nonce,after,revision!!,ack) else if(v2) AirFeedbackAuth.requestV2(nonce,after,revision!!) else AirFeedbackAuth.request(nonce,after)
        val key=security.list().firstOrNull { AirFeedbackAuth.valid(it.hash,body,request.optString("mac")) }?.hash ?: run { rejectedAt=now();return }
        synchronized(nonces) {
            val now=System.nanoTime()/1_000_000
            nonces.entries.removeIf { now-it.value>60_000 }
            require(nonce !in nonces && nonces.size<512)
            nonces[nonce]=now
        }
        authenticatedAt=now();peerVersion=version
        if(version>=3) {
            launches.authorize(key)
            if(ack.isNotEmpty()) { val a=JSONObject(ack);launches.ack(key,a);a.optJSONObject("listener")?.let { overlayPermission=it.optBoolean("overlay") } }
        }
        val value=JSONObject(events.poll(after,stateAfter=revision)).put("maxVersion",3)
        if(version>=3) { value.put("version",3).put("capability","apps-v1");launches.payload(key)?.let { value.put("launch",it) } }
        val result=value.toString()
        if(closed || !allowedPeer(socket.inetAddress) || !security.isPaired(key)) return
        val json=JSONObject().put("body",result).put("mac",AirFeedbackAuth.sign(key,"$nonce:$result"))
        socket.getOutputStream().write((json.toString()+"\n").toByteArray(Charsets.UTF_8))
    }
    override fun close() {
        closed=true;events.close();runCatching { server.close() };clients.forEach { runCatching { it.close() } };workers.shutdownNow()
    }
    companion object {
        fun readLine(input: InputStream): String {
            val bytes=java.io.ByteArrayOutputStream()
            while(bytes.size()<4096) { val n=input.read();check(n>=0) { "Feedback disconnected" };if(n==10) return bytes.toString("UTF-8");bytes.write(n) }
            error("Feedback packet too large")
        }
    }
}
