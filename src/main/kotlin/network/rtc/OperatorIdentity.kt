package org.koremc.network.rtc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.hours

private val TOKEN_LIFETIME = 1.hours

private val base64Url = Base64.getUrlEncoder().withoutPadding()

private val JWT_HEADER = buildJsonObject {
    put("alg", "ES384")
    put("typ", "JWT")
}

private val JWS_HEADER = buildJsonObject { put("alg", "ES384") }

/**
 * The server operator's long-lived identity. Every SDP answer carries an
 * `a=identity` assertion signed with this key, and the client refuses answers
 * without one. Over plain HTTP, clients pin the public key the first time they
 * connect, so it must survive restarts: changing it re-prompts every player.
 */
class OperatorIdentity(private val keyPair: KeyPair, private val domain: String, private val issuer: String) {
    private val jwk = (keyPair.public as ECPublicKey).let { key ->
        buildJsonObject {
            put("kty", "EC")
            put("crv", "P-384")
            put("x", base64Url.encodeToString(key.w.affineX.toUnsigned(48)))
            put("y", base64Url.encodeToString(key.w.affineY.toUnsigned(48)))
        }
    }

    /**
     * The value of the answer's `a=identity` attribute: a self-signed JWT
     * carrying the public key as `cpk`, plus a detached JWS over the answer's
     * [fingerprints] that binds this identity to the DTLS certificate.
     */
    fun assert(fingerprints: List<Fingerprint>, now: Instant = Instant.now()): String {
        val token = buildJsonObject {
            put("cpk", jwk)
            put("iss", issuer)
            put("iat", now.epochSecond)
            put("exp", now.epochSecond + TOKEN_LIFETIME.inWholeSeconds)
        }
        val assertion = buildJsonObject {
            put("token", jws(JWT_HEADER, token.toString()))
            put("fingerprints", jws(JWS_HEADER, canonicalFingerprints(fingerprints), detached = true))
        }
        val envelope = buildJsonObject {
            putJsonObject("idp") {
                put("domain", domain)
                put("protocol", "default")
            }
            put("assertion", assertion.toString())
        }
        return Base64.getEncoder().encodeToString(envelope.toString().toByteArray())
    }

    /**
     * Compact JWS (`header.payload.signature`) signed with ES384. A [detached] JWS
     * leaves the payload out (`header..signature`) for the verifier to rebuild.
     */
    private fun jws(header: JsonObject, payload: String, detached: Boolean = false): String {
        val encodedHeader = base64Url.encodeToString(header.toString().toByteArray())
        val encodedPayload = base64Url.encodeToString(payload.toByteArray())

        // JOSE wants the raw R||S signature, not the DER encoding the JDK produces by default.
        val signature = Signature.getInstance("SHA384withECDSAinP1363Format").run {
            initSign(keyPair.private)
            update("$encodedHeader.$encodedPayload".toByteArray())
            base64Url.encodeToString(sign())
        }
        return if (detached) "$encodedHeader..$signature" else "$encodedHeader.$encodedPayload.$signature"
    }

    companion object {
        /** Loads the operator key from [file], generating and saving a new one on first run. */
        fun loadOrCreate(file: Path, domain: String, issuer: String): OperatorIdentity {
            val keyPair = if (file.exists()) readKeyPair(file) else generateKeyPair().also { writeKeyPair(file, it) }
            return OperatorIdentity(keyPair, domain, issuer)
        }
    }
}

/**
 * The fingerprint JSON both sides sign without sending, so it must be
 * byte-for-byte canonical: sorted keys and no whitespace.
 */
fun canonicalFingerprints(fingerprints: List<Fingerprint>): String = buildJsonObject {
    putJsonArray("fingerprint") {
        fingerprints.forEach {
            addJsonObject {
                put("algorithm", it.algorithm)
                put("digest", it.digest)
            }
        }
    }
}.toString()

private fun generateKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()

private val PEM_BLOCK = Regex("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----")

private fun readKeyPair(file: Path): KeyPair {
    val blocks = PEM_BLOCK.findAll(file.readText())
        .associate { it.groupValues[1] to Base64.getMimeDecoder().decode(it.groupValues[2]) }
    val factory = KeyFactory.getInstance("EC")
    return KeyPair(
        factory.generatePublic(X509EncodedKeySpec(blocks["PUBLIC KEY"] ?: error("No public key in $file"))),
        factory.generatePrivate(PKCS8EncodedKeySpec(blocks["PRIVATE KEY"] ?: error("No private key in $file"))),
    )
}

private fun writeKeyPair(file: Path, keyPair: KeyPair) {
    if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
        file.createFile(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        file.createFile()
    }
    file.writeText(pem("PRIVATE KEY", keyPair.private.encoded) + pem("PUBLIC KEY", keyPair.public.encoded))
}

private fun pem(type: String, der: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
}

/** Big-endian magnitude left-padded to [size] bytes, as JWK coordinates require. */
private fun BigInteger.toUnsigned(size: Int): ByteArray {
    val bytes = toByteArray().takeLast(size).toByteArray()
    return ByteArray(size - bytes.size) + bytes
}
