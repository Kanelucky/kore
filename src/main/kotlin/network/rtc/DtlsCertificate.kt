package org.koremc.network.rtc

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * The certificate presented during the DTLS handshake. WebRTC peers check it
 * against the SDP fingerprint instead of a CA, so a fresh self-signed
 * certificate per run is enough. What clients pin is the operator key in
 * [OperatorIdentity], which signs this fingerprint.
 */
class DtlsCertificate private constructor(val keyPair: KeyPair, val certificate: X509Certificate) {
    val fingerprint = Fingerprint.of(certificate)

    /** Key managers that present this certificate, for building an `SSLContext`. */
    val keyManagers: Array<KeyManager> by lazy {
        // The store only lives in memory, but PKCS12 still insists on a password.
        val password = "kore".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("dtls", keyPair.private, password, arrayOf(certificate))
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
    }

    companion object {
        fun generate(validity: Duration = 30.days): DtlsCertificate {
            val keyPair = KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()

            // The JDK can parse certificates but has no public API to build one, and pulling in
            // BouncyCastle for this alone is overkill. A minimal v3 certificate is a few DER structures.
            val now = Instant.now()
            val name = der(SEQUENCE, der(SET, der(SEQUENCE, COMMON_NAME, der(UTF8_STRING, "kore".toByteArray()))))
            val tbs = der(
                SEQUENCE,
                der(VERSION, der(INTEGER, byteArrayOf(2))),
                der(INTEGER, BigInteger(63, SecureRandom()).inc().toByteArray()),
                ECDSA_WITH_SHA256,
                name,
                der(SEQUENCE, utcTime(now - 1.days.toJavaDuration()), utcTime(now + validity.toJavaDuration())),
                name,
                keyPair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
            val encoded = der(SEQUENCE, tbs, ECDSA_WITH_SHA256, der(BIT_STRING, byteArrayOf(0) + signature))

            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(encoded.inputStream()) as X509Certificate
            return DtlsCertificate(keyPair, certificate)
        }
    }
}

private const val INTEGER = 0x02
private const val BIT_STRING = 0x03
private const val UTF8_STRING = 0x0C
private const val UTC_TIME = 0x17
private const val SEQUENCE = 0x30
private const val SET = 0x31
private const val VERSION = 0xA0

/** AlgorithmIdentifier for ecdsa-with-SHA256 (1.2.840.10045.4.3.2). */
private val ECDSA_WITH_SHA256 = hex("300A06082A8648CE3D040302")

/** OID for commonName (2.5.4.3). */
private val COMMON_NAME = hex("0603550403")

private val UTC_TIME_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

private fun utcTime(instant: Instant) = der(UTC_TIME, UTC_TIME_FORMAT.format(instant).toByteArray())

private fun der(tag: Int, vararg content: ByteArray): ByteArray {
    val body = content.fold(ByteArray(0), ByteArray::plus)
    val length = when {
        body.size < 0x80 -> byteArrayOf(body.size.toByte())
        body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
        else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
    }
    return byteArrayOf(tag.toByte()) + length + body
}

private fun hex(value: String) = HexFormat.of().parseHex(value)
