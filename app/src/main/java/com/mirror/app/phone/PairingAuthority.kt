package com.mirror.app.phone

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** No Android dependency: the HTTP security boundary is exercised by JVM tests. */
class PairingAuthority(saved: String = "{}", private val persist: (String) -> Unit = {},
                       private val now: () -> Long = { System.currentTimeMillis() },
                       private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Device(val hash: String, val name: String, var lastSeen: Long)
    data class PairResult(val status: Int, val token: String? = null, val error: String? = null)
    private val random = SecureRandom()
    private val state = runCatching { JSONObject(saved) }.getOrElse { JSONObject() }
    val cameraId: String = state.optString("cameraId").ifEmpty { randomToken() }
    private val devices = LinkedHashMap<String, Device>()
    private val blockedAt = LinkedHashMap<String, Long>()
    private var code: ByteArray? = null
    private var expires = 0L
    private var wrong = 0
    private var lockedUntil = 0L
    var onRevoked: ((String) -> Unit)? = null
    init {
        val list = state.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until list.length()) {
            val d = list.getJSONObject(i)
            val hash = d.getString("hash")
            devices[hash] = Device(hash, d.optString("name", "TV"), d.optLong("lastSeen"))
        }
        save()
    }
    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    fun tokenHash(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value))
    private fun save() {
        state.put("cameraId", cameraId).put("devices", JSONArray().apply {
            devices.values.forEach { put(JSONObject().put("hash", it.hash).put("name", it.name).put("lastSeen", it.lastSeen)) }
        })
        persist(state.toString())
    }
    /** Shared by server instances so Wi-Fi recovery cannot reset notification throttling. */
    @Synchronized fun shouldNotifyBlocked(ip: String): Boolean {
        val time = elapsed()
        val previous = blockedAt[ip]
        if (previous != null && time - previous < 60_000) return false
        if (blockedAt.size >= 256) blockedAt.entries.removeIf { time - it.value >= 60_000 }
        if (blockedAt.size >= 256) return false
        blockedAt[ip] = time
        return true
    }
    @Synchronized fun showCode(): String? {
        if (elapsed() < lockedUntil) return null
        val value = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
        code = digest(value); expires = elapsed() + 120_000; wrong = 0
        return value
    }
    @Synchronized fun hideCode() { code = null; expires = 0 }
    @Synchronized fun remainingSeconds(): Long = ((expires - elapsed()).coerceAtLeast(0) + 999) / 1000
    @Synchronized fun lockSeconds(): Long = ((lockedUntil - elapsed()).coerceAtLeast(0) + 999) / 1000
    @Synchronized fun pair(value: String, deviceId: String, name: String): PairResult {
        if (elapsed() < lockedUntil) return PairResult(429, error = "Too many attempts")
        val expected = code
        if (expected == null || elapsed() >= expires) { hideCode(); return PairResult(403, error = "Open Pair new TV on your phone and generate a code") }
        val matches = MessageDigest.isEqual(expected, digest(value))
        if (!matches || !value.matches(Regex("[0-9]{6}"))) {
            if (++wrong >= 5) { hideCode(); lockedUntil = elapsed() + 60_000; return PairResult(429, error = "Too many attempts") }
            return PairResult(403, error = "Incorrect pairing code")
        }
        if (deviceId.isBlank() || name.isBlank()) return PairResult(400, error = "Device identity is required")
        val token = randomToken(); val hash = tokenHash(token)
        devices[hash] = Device(hash, name.take(80).filter { !it.isISOControl() }, now())
        save(); return PairResult(200, token)
    }
    @Synchronized fun authenticate(token: String): Device? {
        if (token.length !in 40..64) return null
        val device = devices[tokenHash(token)] ?: return null
        val time = now()
        if (time - device.lastSeen >= 60_000) { device.lastSeen = time; save() }
        return device.copy()
    }
    @Synchronized fun isPaired(hash: String): Boolean = devices.containsKey(hash)
    @Synchronized fun list(): List<Device> = devices.values.map { it.copy() }
    fun remove(hash: String) {
        synchronized(this) { devices.remove(hash); save() }
        onRevoked?.invoke(hash)
    }
    @Synchronized fun setBrowserPassword(password: String?) {
        if (password == null) { state.remove("browserSalt"); state.remove("browserHash") }
        else { val salt = randomToken(); state.put("browserSalt", salt).put("browserHash", tokenHash(salt + password)) }
        save()
    }
    @Synchronized fun browserEnabled(): Boolean = state.has("browserHash")
    @Synchronized fun browserAuth(header: String): Boolean {
        if (!browserEnabled() || !header.startsWith("Basic ", true)) return false
        val password = runCatching { String(Base64.getDecoder().decode(header.substring(6)), Charsets.UTF_8).substringAfter(':', "") }.getOrNull() ?: return false
        return MessageDigest.isEqual(state.getString("browserHash").toByteArray(), tokenHash(state.getString("browserSalt") + password).toByteArray())
    }
}
