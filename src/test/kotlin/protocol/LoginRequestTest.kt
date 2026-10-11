package org.koremc.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.koremc.auth.base64Der
import org.koremc.auth.ecKeyPair
import org.koremc.auth.sameKey
import org.koremc.auth.signJwt
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val XUID = "2535412345678901"

class LoginRequestTest {
    private val client = ecKeyPair()
    private val clientKey = client.public as ECPublicKey

    @Test
    fun `current clients carry their identity in the token`() {
        val token = token(buildJsonObject {
            put("xid", XUID)
            put("xname", "Steve")
            put("cpk", clientKey.base64Der())
        })
        val request = parseConnectionRequest(connectionRequest(tokenAuthentication(token, chain = listOf(token)), clientDataToken(client.private)))

        assertEquals(AuthenticationType.FULL, request.authenticationType)
        assertEquals("Steve", request.identity.displayName)
        assertEquals(XUID, request.identity.xuid)
        assertEquals(uuidFromXuid(XUID), request.identity.uuid)
        assertTrue(sameKey(clientKey, request.identity.publicKey))
        assertEquals(ClientInfo("1.26.50", 7, "Desktop", "en_GB"), request.clientInfo)
        assertEquals(token, request.token)
        assertEquals(1, request.chain.size)
    }

    @Test
    fun `the client key can also arrive as a JWK`() {
        val token = token(buildJsonObject {
            put("xid", XUID)
            put("xname", "Steve")
            putJsonObject("cpk") {
                put("kty", "EC")
                put("crv", "P-384")
                put("x", base64Url(clientKey.w.affineX.toByteArray().takeLast(48).toByteArray()))
                put("y", base64Url(clientKey.w.affineY.toByteArray().takeLast(48).toByteArray()))
            }
        })
        val request = parseConnectionRequest(connectionRequest(tokenAuthentication(token), clientDataToken(client.private)))

        assertTrue(sameKey(clientKey, request.identity.publicKey))
    }

    @Test
    fun `offline clients name their own uuid`() {
        val uuid = UUID.randomUUID()
        val token = token(buildJsonObject {
            put("xid", "")
            put("xname", "Alex")
            put("leguuid", uuid.toString())
            put("cpk", clientKey.base64Der())
        })
        val request = parseConnectionRequest(connectionRequest(tokenAuthentication(token, type = 2, chain = listOf("")), clientDataToken(client.private)))

        assertEquals(AuthenticationType.SELF_SIGNED, request.authenticationType)
        assertEquals(uuid, request.identity.uuid)
        assertNull(request.identity.xuid)
    }

    @Test
    fun `older clients carry their identity at the end of the chain`() {
        val uuid = UUID.randomUUID()
        val identityClaim = token(buildJsonObject {
            put("identityPublicKey", clientKey.base64Der())
            putJsonObject("extraData") {
                put("displayName", "Steve")
                put("identity", uuid.toString())
                put("XUID", XUID)
            }
        })
        val legacy = buildJsonObject {
            putJsonArray("chain") {
                add(token(JsonObject(emptyMap())))
                add(identityClaim)
            }
        }
        val request = parseConnectionRequest(connectionRequest(legacy.toString(), clientDataToken(client.private)))

        assertNull(request.authenticationType)
        assertNull(request.token)
        assertEquals("Steve", request.identity.displayName)
        assertEquals(uuid, request.identity.uuid)
        assertTrue(sameKey(clientKey, request.identity.publicKey))
    }

    @Test
    fun `uuids from xuids are name-based`() {
        val uuid = uuidFromXuid(XUID)

        assertEquals(3, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(uuid, uuidFromXuid(XUID))
    }

    @Test
    fun `malformed requests are rejected`() {
        val clientData = clientDataToken(client.private)

        assertFailsWith<PacketException> { parseConnectionRequest(byteArrayOf(1, 2)) }
        assertFailsWith<PacketException> { parseConnectionRequest(connectionRequest("not json", clientData)) }
        assertFailsWith<PacketException> { parseConnectionRequest(connectionRequest(tokenAuthentication("not.a-jwt"), clientData)) }
        assertFailsWith<PacketException> {
            parseConnectionRequest(connectionRequest(tokenAuthentication(token(buildJsonObject { put("xname", "NoKey") })), clientData))
        }
    }

    private fun token(claims: JsonObject) = signJwt(claims, client.private, "ES384")

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
