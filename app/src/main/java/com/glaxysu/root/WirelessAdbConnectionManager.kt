package com.glaxysu.root

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * Persistent ADB identity used by libadb-android.
 *
 * The identity is generated once and kept in the app-private files directory so
 * Wireless debugging only needs to be paired once per installation.
 */
class WirelessAdbConnectionManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        var storedPrivateKey = readPrivateKey(context)
        var storedCertificate = readCertificate(context)
        if (storedPrivateKey == null || storedCertificate == null) {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val keyPair = generator.generateKeyPair()
            storedPrivateKey = keyPair.private
            storedCertificate = createCertificate(keyPair.public, storedPrivateKey)
            writePrivateKey(context, storedPrivateKey)
            writeCertificate(context, storedCertificate)
        }
        privateKey = storedPrivateKey
        certificate = storedCertificate
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Glaxy Su"

    companion object {
        private const val PRIVATE_KEY_FILE = "wireless-adb-private.key"
        private const val CERTIFICATE_FILE = "wireless-adb-cert.pem"
        private const val CERTIFICATE_LIFETIME_MILLIS = 365L * 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: WirelessAdbConnectionManager? = null

        @Synchronized
        fun getInstance(context: Context): WirelessAdbConnectionManager {
            return instance ?: WirelessAdbConnectionManager(context.applicationContext).also {
                instance = it
            }
        }

        private fun createCertificate(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
            val subject = "CN=Glaxy Su"
            val algorithmName = "SHA256withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + CERTIFICATE_LIFETIME_MILLIS)
            val extensions = CertificateExtensions().apply {
                set(
                    "SubjectKeyIdentifier",
                    SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
                )
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val name = X500Name(subject)
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set(
                    "serialNumber",
                    CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE),
                )
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(name))
                set("extensions", extensions)
            }
            return X509CertImpl(info).also { it.sign(privateKey, algorithmName) }
        }

        private fun readCertificate(context: Context): Certificate? {
            val file = File(context.filesDir, CERTIFICATE_FILE)
            if (!file.exists()) return null
            FileInputStream(file).use { input ->
                return CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
        }

        private fun writeCertificate(context: Context, certificate: Certificate) {
            val file = File(context.filesDir, CERTIFICATE_FILE)
            val encoder = BASE64Encoder()
            FileOutputStream(file).use { output ->
                output.write(X509Factory.BEGIN_CERT.toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                encoder.encode(certificate.encoded, output)
                output.write('\n'.code)
                output.write(X509Factory.END_CERT.toByteArray(Charsets.UTF_8))
            }
        }

        private fun readPrivateKey(context: Context): PrivateKey? {
            val file = File(context.filesDir, PRIVATE_KEY_FILE)
            if (!file.exists()) return null
            val bytes = ByteArray(file.length().toInt())
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
            }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }

        private fun writePrivateKey(context: Context, privateKey: PrivateKey) {
            FileOutputStream(File(context.filesDir, PRIVATE_KEY_FILE)).use { output ->
                output.write(privateKey.encoded)
            }
        }
    }
}
