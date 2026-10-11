package org.koremc

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.writeByteArray
import org.koremc.protocol.writeVarUInt
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROTOCOL = 2193

class SessionTest {
    private val sent = mutableListOf<ByteArray>()
    private val session = Session(PROTOCOL, networkId = "9876543210123456789") { sent += it }

    /** The client's side of batching, which switches on compression when NetworkSettings arrives. */
    private val client = BatchCodec()

    @Test
    fun `a matching protocol gets network settings`() {
        clientSends(requestNetworkSettings(PROTOCOL))

        val reply = client.decode(sent.single()).single()
        assertContentEquals(bytes(0x8F, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), reply)
    }

    @Test
    fun `older clients are told they're out of date`() {
        clientSends(requestNetworkSettings(PROTOCOL - 1))

        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x01), client.decode(sent.single()).single())
    }

    @Test
    fun `newer clients are told the server is out of date, and nothing more`() {
        clientSends(requestNetworkSettings(PROTOCOL + 1))
        clientSends(requestNetworkSettings(PROTOCOL))

        assertEquals(1, sent.size)
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x02), client.decode(sent.single()).single())
    }

    @Test
    fun `login is read once compression is on`() {
        clientSends(requestNetworkSettings(PROTOCOL))
        client.enableCompression(256)

        // The skin makes the batch big enough to be compressed, as real logins are.
        clientSends(login(PROTOCOL, displayName = "Steve", skinData = "A".repeat(4096)))

        val login = session.login
        assertEquals("Steve", login?.identity?.displayName)
        assertEquals("1.26.50", login?.clientInfo?.gameVersion)
    }

    @Test
    fun `packets out of order are ignored`() {
        clientSends(login(PROTOCOL, displayName = "Steve", skinData = ""))

        assertNull(session.login)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a malformed batch doesn't derail the session`() {
        clientSends(requestNetworkSettings(PROTOCOL))
        client.enableCompression(256)

        session.receive(bytes(0x05, 0x01))
        clientSends(login(PROTOCOL, displayName = "Steve", skinData = ""))

        assertEquals("Steve", session.login?.identity?.displayName)
    }

    private fun clientSends(packet: ByteArray) = session.receive(client.encode(listOf(packet)))

    private fun requestNetworkSettings(protocol: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(protocol))

    private fun login(protocol: Int, displayName: String, skinData: String): ByteArray {
        val token = jwt(buildJsonObject {
            put("xid", "2535412345678901")
            put("xname", displayName)
            put("cpk", "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEclientkey")
        })
        val authentication = buildJsonObject {
            put("AuthenticationType", 0)
            put("Certificate", """{"chain":[]}""")
            put("Token", token)
        }.toString().toByteArray()
        val clientData = jwt(buildJsonObject {
            put("GameVersion", "1.26.50")
            put("SkinData", skinData)
        }).toByteArray()

        val connectionRequest = ByteBufUtil.getBytes(
            Unpooled.buffer()
                .writeIntLE(authentication.size).writeBytes(authentication)
                .writeIntLE(clientData.size).writeBytes(clientData),
        )
        return ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(1).writeInt(protocol).writeByteArray(connectionRequest))
    }

    private fun jwt(claims: JsonObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString("""{"alg":"ES384"}""".toByteArray())}.${encoder.encodeToString(claims.toString().toByteArray())}.c2ln"
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
