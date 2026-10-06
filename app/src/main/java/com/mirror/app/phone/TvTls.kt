package com.mirror.app.phone

import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate

/** Isolated, pure Java TLS 1.3. Never changes Android's global security providers. */
internal class TvTls(private val key: PrivateKey, private val certificate: X509Certificate) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {
    private var binding: ByteArray? = null
    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete(); binding = context.exportKeyingMaterial("adb-label\u0000", null, 64)
    }
    override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv13)
    override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
        override fun notifyServerCertificate(server: TlsServerCertificate) {
            // ADB uses self-signed certificates. Pairing authenticates the device through SPAKE2,
            // and adbd authorizes our persisted client key. Its server certificate can rotate.
            if (server.certificate.isEmpty) throw TlsFatalAlert(AlertDescription.bad_certificate)
        }
        override fun getClientCredentials(request: CertificateRequest): TlsCredentials {
            val signature = SignatureAndHashAlgorithm.getInstance(HashAlgorithm.Intrinsic, SignatureAlgorithm.rsa_pss_rsae_sha256)
            if (request.supportedSignatureAlgorithms?.contains(signature) != true) throw TlsFatalAlert(AlertDescription.handshake_failure)
            val chain = Certificate(request.certificateRequestContext ?: ByteArray(0), arrayOf(CertificateEntry(crypto.createCertificate(certificate.encoded), null)))
            return BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context), crypto as BcTlsCrypto,
                PrivateKeyFactory.createKey(key.encoded), chain, signature)
        }
    }
    fun pairingBinding(): ByteArray = binding?.clone() ?: throw java.io.IOException("Pairing binding unavailable")
}
