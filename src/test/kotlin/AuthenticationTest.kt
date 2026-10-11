package org.koremc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.XUID
import org.koremc.auth.base64Der
import org.koremc.auth.ecKeyPair
import org.koremc.auth.signJwt
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.parseConnectionRequest
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.uuidFromXuid
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticationTest {
    private val authority = TestAuthority()
    private val client = ecKeyPair()
    private val clientKey = client.public as ECPublicKey
    private val verifier = MultiplayerTokenVerifier(keys = { authority.keys })
    private val online = LoginAuthenticator(verifier, onlineMode = true)
    private val offline = LoginAuthenticator(verifier, onlineMode = false)

    @Test
    fun `a verified token with matching keys authenticates`() {
        val player = online.authenticate(login(authority.multiplayerToken(clientKey)), offerKey = clientKey)

        assertTrue(player.verified)
        assertEquals("Steve", player.displayName)
        assertEquals(XUID, player.xuid)
        assertEquals(uuidFromXuid(XUID), player.uuid)
    }

    @Test
    fun `online mode rejects tokens the service didn't issue`() {
        val forged = TestAuthority().multiplayerToken(clientKey)

        assertFailsWith<AuthenticationException> { online.authenticate(login(forged), offerKey = clientKey) }
    }

    @Test
    fun `online mode rejects logins without a token`() {
        assertFailsWith<AuthenticationException> { online.authenticate(legacyLogin(), offerKey = clientKey) }
    }

    @Test
    fun `online mode requires Xbox Live`() {
        val guest = authority.multiplayerToken(clientKey, xuid = "")

        assertFailsWith<AuthenticationException> { online.authenticate(login(guest), offerKey = clientKey) }
    }

    @Test
    fun `client data must be signed by the token's key`() {
        val login = login(authority.multiplayerToken(clientKey), clientDataKey = ecKeyPair().private)

        assertFailsWith<AuthenticationException> { online.authenticate(login, offerKey = clientKey) }
    }

    @Test
    fun `the login must carry the key the offer proved`() {
        // A valid login, but lifted onto a connection that proved a different key.
        val login = login(authority.multiplayerToken(clientKey))

        assertFailsWith<AuthenticationException> { online.authenticate(login, offerKey = ecKeyPair().public as ECPublicKey) }
    }

    @Test
    fun `offline mode admits identities it can't verify`() {
        val player = offline.authenticate(login(selfSignedToken()), offerKey = null)

        assertFalse(player.verified)
        assertEquals("Alex", player.displayName)
        assertEquals(UUID.fromString("5b5d0c6e-0d57-4bb1-9b0e-6b2d7f9b8c11"), player.uuid)
    }

    @Test
    fun `offline mode still checks the client data signature and offer binding`() {
        assertFailsWith<AuthenticationException> {
            offline.authenticate(login(selfSignedToken(), clientDataKey = ecKeyPair().private), offerKey = null)
        }
        assertFailsWith<AuthenticationException> {
            offline.authenticate(login(selfSignedToken()), offerKey = ecKeyPair().public as ECPublicKey)
        }
    }

    @Test
    fun `offline mode still recognises verified tokens`() {
        assertTrue(offline.authenticate(login(authority.multiplayerToken(clientKey)), offerKey = null).verified)
    }

    private fun login(token: String, clientDataKey: PrivateKey = client.private): LoginRequest =
        parseConnectionRequest(connectionRequest(tokenAuthentication(token), clientDataToken(clientDataKey)))

    /** What an offline client sends: a token it signed itself, naming its own UUID. */
    private fun selfSignedToken() = signJwt(
        buildJsonObject {
            put("xid", "")
            put("xname", "Alex")
            put("leguuid", "5b5d0c6e-0d57-4bb1-9b0e-6b2d7f9b8c11")
            put("cpk", clientKey.base64Der())
            put("exp", Instant.now().epochSecond + 3600)
        },
        client.private,
        "ES384",
    )

    /** An older-style login: no token, just the chain with the identity in its last link. */
    private fun legacyLogin(): LoginRequest {
        val identity = signJwt(
            buildJsonObject {
                put("identityPublicKey", clientKey.base64Der())
                putJsonObject("extraData") {
                    put("displayName", "Steve")
                    put("identity", UUID.randomUUID().toString())
                    put("XUID", XUID)
                }
            },
            client.private,
            "ES384",
        )
        val authentication = buildJsonObject { putJsonArray("chain") { add(identity) } }.toString()
        return parseConnectionRequest(connectionRequest(authentication, clientDataToken(client.private)))
    }
}
