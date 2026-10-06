package com.mirror.app.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** RSA identity is encrypted with a non-exportable Android Keystore AES key, outside backups. */
internal object TvCredentials {
    private const val ALIAS = "mirror_tv_adb_storage"
    data class Identity(val key: PrivateKey, val certificate: X509Certificate)
    private fun file(context: Context) = File(context.noBackupFilesDir, "tv_adb_identity")
    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(context: Context, create: Boolean = false): Identity {
        val target = file(context)
        if (target.exists()) {
            val bytes = target.readBytes(); require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            val clear = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                return Identity(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(json.getString("key"), Base64.NO_WRAP))),
                    CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(json.getString("certificate"), Base64.NO_WRAP).inputStream()) as X509Certificate)
            } finally { clear.fill(0) }
        }
        check(create) { "Pair Mirror with your TV in Settings → TV launch." }
        val random = SecureRandom(); val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val name = X500Name("CN=Mirror Clock"); val now = System.currentTimeMillis(); val provider = BouncyCastleProvider()
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger(128, random).abs(), Date(now - 86400000), Date(now + 20L * 365 * 86400000), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
        val clear = JSONObject().put("key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .put("certificate", Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)).toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, storageKey()) }
            val temporary = File(context.noBackupFilesDir, "tv_adb_identity.tmp")
            temporary.outputStream().use { it.write(cipher.iv); it.write(cipher.doFinal(clear)) }
            check(temporary.renameTo(target)) { "Cannot save TV identity" }
        } finally { clear.fill(0) }
        return Identity(pair.private, certificate)
    }
    fun forget(context: Context) { file(context).delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) } }
}
