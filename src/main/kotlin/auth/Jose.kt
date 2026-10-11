package org.koremc.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Thrown when a token or key is malformed, or doesn't verify. */
class JoseException(message: String) : Exception(message)

/** JCA names for the two algorithms Bedrock uses: RS256 for the auth service, ES384 for anything a client signs. */
private val SIGNATURE_ALGORITHMS = mapOf(
    "RS256" to "SHA256withRSA",
    "ES384" to "SHA384withECDSAinP1363Format",
)

private val CURVES = mapOf("P-256" to "secp256r1", "P-384" to "secp384r1", "P-521" to "secp521r1")

/**
 * A compact JWS (`header.payload.signature`), decoded but not verified.
 * Nothing in [claims] can be trusted until [verify] has returned true.
 */
class Jws private constructor(
    val header: JsonObject,
    val payload: ByteArray,
    private val signingInput: ByteArray,
    private val signature: ByteArray,
) {
    val algorithm get() = header.string("alg")
    val keyId get() = header.string("kid")

    /** The payload read as JWT claims. */
    val claims: JsonObject by lazy { parseJsonObject(payload.decodeToString()) }

    /**
     * Checks the signature with [key], accepting only [algorithm]. The caller
     * always says which algorithm it expects, so a token can never talk Kore
     * into a weaker one, or into `none`.
     */
    fun verify(key: PublicKey, algorithm: String): Boolean {
        if (this.algorithm != algorithm) return false
        val jca = SIGNATURE_ALGORITHMS[algorithm] ?: return false
        return try {
            Signature.getInstance(jca).run {
                initVerify(key)
                update(signingInput)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    companion object {
        fun parse(compact: String): Jws {
            val parts = compact.split('.')
            if (parts.size != 3) throw JoseException("not a compact JWS")
            return Jws(parseJsonObject(decodeBase64Url(parts[0]).decodeToString()), decodeBase64Url(parts[1]), signingInput(parts[0], parts[1]), decodeBase64Url(parts[2]))
        }

        /** A detached JWS (`header..signature`), where the verifier supplies the [payload] itself. */
        fun parseDetached(compact: String, payload: ByteArray): Jws {
            val parts = compact.split('.')
            if (parts.size != 3 || parts[1].isNotEmpty()) throw JoseException("not a detached JWS")
            val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
            return Jws(parseJsonObject(decodeBase64Url(parts[0]).decodeToString()), payload, signingInput(parts[0], encodedPayload), decodeBase64Url(parts[2]))
        }

        private fun signingInput(header: String, payload: String) = "$header.$payload".toByteArray()
    }
}

/**
 * A client's public key from a `cpk` claim. Login tokens carry it as base64
 * DER; offer tokens from 26.40 on carry it as a JWK.
 */
fun parseClientKey(cpk: JsonElement?): ECPublicKey = when (cpk) {
    is JsonObject -> ecKeyFromJwk(cpk)
    is JsonPrimitive -> ecKeyFromDer(cpk.contentOrNull ?: throw JoseException("cpk is null"))
    else -> throw JoseException("token has no cpk")
}

/** An EC public key as base64 DER (X.509 SubjectPublicKeyInfo), the way Bedrock writes them. */
fun ecKeyFromDer(base64: String): ECPublicKey {
    val der = try {
        Base64.getDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
        throw JoseException("public key isn't base64")
    }
    return try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
    } catch (e: GeneralSecurityException) {
        throw JoseException("not an EC public key: ${e.message}")
    }
}

fun ecKeyFromJwk(jwk: JsonObject): ECPublicKey {
    if (jwk.string("kty") != "EC") throw JoseException("JWK isn't an EC key")
    val curve = CURVES[jwk.string("crv")] ?: throw JoseException("unsupported curve ${jwk.string("crv")}")
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(curve)) }.getParameterSpec(ECParameterSpec::class.java)
    val point = ECPoint(jwk.unsigned("x"), jwk.unsigned("y"))
    return try {
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    } catch (e: GeneralSecurityException) {
        throw JoseException("invalid EC key: ${e.message}")
    }
}

fun rsaKeyFromJwk(jwk: JsonObject): RSAPublicKey {
    if (jwk.string("kty") != "RSA") throw JoseException("JWK isn't an RSA key")
    return try {
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(jwk.unsigned("n"), jwk.unsigned("e"))) as RSAPublicKey
    } catch (e: GeneralSecurityException) {
        throw JoseException("invalid RSA key: ${e.message}")
    }
}

/** Whether two keys are the same key, whichever format each arrived in. */
fun sameKey(a: PublicKey, b: PublicKey) = a.encoded.contentEquals(b.encoded)

fun parseJsonObject(json: String): JsonObject = try {
    Json.parseToJsonElement(json).jsonObject
} catch (e: SerializationException) {
    throw JoseException("malformed JSON: ${e.message}")
} catch (e: IllegalArgumentException) {
    throw JoseException("expected a JSON object")
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.unsigned(key: String) = BigInteger(1, decodeBase64Url(string(key) ?: throw JoseException("JWK has no '$key'")))

private fun decodeBase64Url(value: String): ByteArray = try {
    Base64.getUrlDecoder().decode(value)
} catch (e: IllegalArgumentException) {
    throw JoseException("not base64url")
}
