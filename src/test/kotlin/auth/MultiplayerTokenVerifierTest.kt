package org.koremc.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.security.interfaces.ECPublicKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class MultiplayerTokenVerifierTest {
    private val authority = TestAuthority()
    private val client = ecKeyPair()
    private val clientKey = client.public as ECPublicKey
    private var refreshes = 0
    private val verifier = MultiplayerTokenVerifier(keys = { authority.keys }, onMissingKeys = { refreshes++ })

    @Test
    fun `tokens from the authorization service verify`() {
        val token = verifier.verify(authority.multiplayerToken(clientKey))

        assertEquals("Steve", token.displayName)
        assertEquals(XUID, token.xuid)
        assertTrue(sameKey(clientKey, token.publicKey))
        assertNull(token.legacyUuid)
    }

    @Test
    fun `tokens from anyone else don't`() {
        val impostor = TestAuthority()

        assertFailsWith<JoseException> { verifier.verify(impostor.multiplayerToken(clientKey)) }
        assertFailsWith<JoseException> { verifier.verify(authority.multiplayerToken(clientKey, issuer = "https://example.com/")) }
    }

    @Test
    fun `tokens meant for another audience are rejected`() {
        assertFailsWith<JoseException> { verifier.verify(authority.multiplayerToken(clientKey, audience = "api://somewhere-else")) }
    }

    @Test
    fun `audiences can be a list`() {
        val token = authority.sign(
            buildJsonObject {
                put("iss", ISSUER)
                putJsonArray("aud") {
                    add("api://somewhere-else")
                    add(MULTIPLAYER_AUDIENCE)
                }
                put("exp", Instant.now().epochSecond + 60)
                put("xname", "Steve")
                put("cpk", clientKey.base64Der())
            },
        )

        assertEquals("Steve", verifier.verify(token).displayName)
    }

    @Test
    fun `tokens that expired within the last minute still pass`() {
        val issued = Instant.now() - 1.hours.toJavaDuration()

        verifier.verify(authority.multiplayerToken(clientKey, now = issued, lifetime = 1.hours - 30.seconds))
    }

    @Test
    fun `expired and not-yet-valid tokens are rejected`() {
        val now = Instant.now()

        assertFailsWith<JoseException> {
            verifier.verify(authority.multiplayerToken(clientKey, now = now - 2.hours.toJavaDuration(), lifetime = 1.hours))
        }
        assertFailsWith<JoseException> { checkLifetime(buildJsonObject { put("nbf", now.epochSecond + 300) }, now, requireExpiry = false) }
        assertFailsWith<JoseException> { checkLifetime(buildJsonObject {}, now, requireExpiry = true) }
    }

    @Test
    fun `unknown keys ask for a refresh`() {
        assertFailsWith<JoseException> { verifier.verify(authority.multiplayerToken(clientKey, keyId = "rotated-key")) }
        assertEquals(1, refreshes)
    }

    @Test
    fun `nothing verifies before the keys are loaded`() {
        val cold = MultiplayerTokenVerifier(keys = { null }, onMissingKeys = { refreshes++ })

        assertFailsWith<JoseException> { cold.verify(authority.multiplayerToken(clientKey)) }
        assertEquals(1, refreshes)
    }

    @Test
    fun `self-signed tokens are rejected`() {
        val selfSigned = signJwt(
            buildJsonObject {
                put("iss", ISSUER)
                put("aud", MULTIPLAYER_AUDIENCE)
                put("exp", Instant.now().epochSecond + 60)
                put("xname", "Steve")
                put("cpk", clientKey.base64Der())
            },
            client.private,
            "ES384",
            KEY_ID,
        )

        assertFailsWith<JoseException> { verifier.verify(selfSigned) }
    }
}
