package com.mirror.app.phone.roster

/** A failed parse/commit never poisons retries. Serialized callers also suppress concurrent duplicates. */
internal class AcceptedPdf {
    private var accepted: ByteArray? = null
    @Synchronized fun reset() { accepted=null }
    @Synchronized fun duplicate(bytes:ByteArray):Boolean = accepted?.contentEquals(hash(bytes)) == true
    @Synchronized fun accept(bytes:ByteArray, save:()->Boolean):Boolean {
        val digest=hash(bytes)
        if(accepted?.contentEquals(digest)==true) return true
        if(!save()) return false
        accepted=digest;return true
    }
    private fun hash(bytes:ByteArray):ByteArray {
        require(bytes.size<=20*1024*1024)
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
