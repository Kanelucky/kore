package org.koremc.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

internal const val ISSUER = "https://authorization.franchise.minecraft-services.net/"
internal const val KEY_ID = "3D5C5F6F4FBD601B0A451D7759A8730F34A6DA51"
internal const val XUID = "2535412345678901"

/** Stands in for Minecraft's authorization service: an RSA key, and the published keys a verifier would load. */
internal class TestAuthority {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val keys = AuthKeys(ISSUER, setOf("RS256"), mapOf(KEY_ID to keyPair.public))

    /** Signs any claims the way the service signs its tokens. */
    fun sign(claims: JsonObject, keyId: String = KEY_ID) = signJwt(claims, keyPair.private, "RS256", keyId)

    fun multiplayerToken(
        clientKey: ECPublicKey,
        xuid: String = XUID,
        displayName: String = "Steve",
        issuer: String = ISSUER,
        audience: String = MULTIPLAYER_AUDIENCE,
        now: Instant = Instant.now(),
        lifetime: Duration = 1.hours,
        keyId: String = KEY_ID,
    ) = sign(
        buildJsonObject {
            put("iss", issuer)
            put("aud", audience)
            put("iat", now.epochSecond)
            put("exp", (now + lifetime.toJavaDuration()).epochSecond)
            put("xid", xuid)
            put("xname", displayName)
            put("mid", "ABCDEF0123456789")
            put("cpk", clientKey.base64Der())
        },
        keyId,
    )
}

internal fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()

internal fun signJwt(claims: JsonObject, key: PrivateKey, algorithm: String, keyId: String? = null): String {
    val header = buildJsonObject {
        put("alg", algorithm)
        keyId?.let { put("kid", it) }
    }
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val input = "${encoder.encodeToString(header.toString().toByteArray())}.${encoder.encodeToString(claims.toString().toByteArray())}"
    val jca = if (algorithm == "RS256") "SHA256withRSA" else "SHA384withECDSAinP1363Format"
    val signature = Signature.getInstance(jca).run {
        initSign(key)
        update(input.toByteArray())
        sign()
    }
    return "$input.${encoder.encodeToString(signature)}"
}

internal fun ECPublicKey.base64Der(): String = Base64.getEncoder().encodeToString(encoded)
