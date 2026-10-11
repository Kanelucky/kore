package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val XUID = "2535412345678901"
private const val CLIENT_KEY = "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEclientkey"

class LoginRequestTest {
    private val clientData = buildJsonObject {
        put("GameVersion", "1.26.50")
        put("DeviceOS", 7)
        put("DeviceModel", "Desktop")
        put("LanguageCode", "en_GB")
        put("SkinData", "AAAA")
    }

    @Test
    fun `current clients carry their identity in the token`() {
        val token = jwt(buildJsonObject {
            put("xid", XUID)
            put("xname", "Steve")
            put("mid", "ABCDEF")
            put("cpk", CLIENT_KEY)
        })
        val request = parseConnectionRequest(connectionRequest(newFormat(type = 0, token = token, chain = listOf(jwt(JsonObject(emptyMap()))))))

        assertEquals(AuthenticationType.FULL, request.authenticationType)
        assertEquals(ClaimedIdentity("Steve", XUID, uuidFromXuid(XUID), CLIENT_KEY), request.identity)
        assertEquals(ClientInfo("1.26.50", 7, "Desktop", "en_GB"), request.clientInfo)
        assertEquals(token, request.token)
        assertEquals(1, request.chain.size)
    }

    @Test
    fun `offline clients name their own uuid`() {
        val uuid = UUID.randomUUID()
        val token = jwt(buildJsonObject {
            put("xid", "")
            put("xname", "Alex")
            put("leguuid", uuid.toString())
            put("cpk", CLIENT_KEY)
        })
        val request = parseConnectionRequest(connectionRequest(newFormat(type = 2, token = token, chain = listOf(""))))

        assertEquals(AuthenticationType.SELF_SIGNED, request.authenticationType)
        assertEquals(uuid, request.identity.uuid)
        assertNull(request.identity.xuid)
    }

    @Test
    fun `older clients carry their identity at the end of the chain`() {
        val uuid = UUID.randomUUID()
        val identityClaim = jwt(buildJsonObject {
            put("identityPublicKey", CLIENT_KEY)
            putJsonObject("extraData") {
                put("displayName", "Steve")
                put("identity", uuid.toString())
                put("XUID", XUID)
            }
        })
        val legacy = buildJsonObject { putJsonArray("chain") { add(jwt(JsonObject(emptyMap()))); add(identityClaim) } }
        val request = parseConnectionRequest(connectionRequest(legacy.toString()))

        assertNull(request.authenticationType)
        assertNull(request.token)
        assertEquals(ClaimedIdentity("Steve", XUID, uuid, CLIENT_KEY), request.identity)
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
        assertFailsWith<PacketException> { parseConnectionRequest(byteArrayOf(1, 2)) }
        assertFailsWith<PacketException> { parseConnectionRequest(connectionRequest("not json")) }
        assertFailsWith<PacketException> { parseConnectionRequest(connectionRequest(newFormat(type = 0, token = "not.a-jwt", chain = emptyList()))) }
        assertFailsWith<PacketException> { parseConnectionRequest(connectionRequest(newFormat(type = 0, token = jwt(buildJsonObject { put("xname", "NoKey") }), chain = emptyList()))) }
    }

    private fun newFormat(type: Int, token: String, chain: List<String>) = buildJsonObject {
        put("AuthenticationType", type)
        put("Certificate", buildJsonObject { putJsonArray("chain") { chain.forEach { add(it) } } }.toString())
        put("Token", token)
    }.toString()

    private fun connectionRequest(authentication: String): ByteArray {
        val clientDataToken = jwt(clientData).toByteArray()
        val auth = authentication.toByteArray()
        return ByteBufUtil.getBytes(
            Unpooled.buffer()
                .writeIntLE(auth.size).writeBytes(auth)
                .writeIntLE(clientDataToken.size).writeBytes(clientDataToken),
        )
    }

    /** An unsigned JWT. Kore doesn't check signatures yet, so the signature is just a placeholder. */
    private fun jwt(claims: JsonObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"ES384"}""".toByteArray())
        return "$header.${encoder.encodeToString(claims.toString().toByteArray())}.c2ln"
    }
}
