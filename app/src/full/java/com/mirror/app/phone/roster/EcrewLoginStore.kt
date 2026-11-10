package com.mirror.app.phone.roster

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only encrypted login; excluded from backup and never included in diagnostics. */
internal class EcrewLoginStore(private val context:Context):Autocomplete.StorageDelegate {
    private val file get()=File(context.noBackupFilesDir,"ecrew-login.enc")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("mirror-ecrew-login",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mirror-ecrew-login",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun onLoginSave(login:Autocomplete.LoginEntry) {
        if(!trusted(login.origin) || !trusted(login.formActionOrigin)) return
        runCatching {
            val json=JSONObject().put("origin",login.origin).put("action",login.formActionOrigin).put("username",login.username).put("password",login.password).toString()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
            val bytes=cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val atomic=android.util.AtomicFile(file)
            val out=atomic.startWrite()
            try { out.write(cipher.iv+bytes);atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out);throw e }
        }
    }
    override fun onLoginFetch(domain:String):GeckoResult<Array<Autocomplete.LoginEntry>> {
        if(domain!="ecrew.cebupacificair.com") return GeckoResult.fromValue(emptyArray())
        val entry=runCatching {
            val bytes=file.readBytes();require(bytes.size>28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
            val j=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
            require(trusted(j.getString("origin")) && trusted(j.getString("action")))
            Autocomplete.LoginEntry.Builder().origin(j.getString("origin")).formActionOrigin(j.getString("action")).username(j.getString("username")).password(j.getString("password")).build()
        }.getOrNull()
        return GeckoResult.fromValue(listOfNotNull(entry).toTypedArray())
    }
    companion object {
        fun trusted(origin:String?)=runCatching { val u=java.net.URI(origin ?: "");u.scheme=="https" && u.host=="ecrew.cebupacificair.com" && u.userInfo==null && u.port in listOf(-1,443) }.getOrDefault(false)
        fun clear(c:Context) { android.util.AtomicFile(File(c.noBackupFilesDir,"ecrew-login.enc")).delete() }
    }
}
