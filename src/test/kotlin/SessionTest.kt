package org.koremc

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.XUID
import org.koremc.auth.ecKeyPair
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.encode
import org.koremc.protocol.loginPacket
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.writeString
import org.koremc.protocol.writeVarInt
import org.koremc.protocol.writeVarUInt
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemVersion
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROTOCOL = 2193

class SessionTest {
    private val authority = TestAuthority()
    private val client = ecKeyPair()
    private val clientKey = client.public as ECPublicKey
    private val authenticator = LoginAuthenticator(MultiplayerTokenVerifier(keys = { authority.keys }), onlineMode = true)

    private val config = ServerConfig(protocol = PROTOCOL, viewDistance = 8)
    private val itemRegistry = ItemRegistry(listOf(ItemDefinition("minecraft:stick", 323, componentBased = false, ItemVersion.NONE)))
    private val sent = mutableListOf<ByteArray>()
    private val session = Session(config, itemRegistry, networkId = "9876543210123456789", entityId = 7, offerKey = clientKey, authenticator) { sent += it }

    /** The client's side of batching, which switches on compression when NetworkSettings arrives. */
    private val batches = BatchCodec()

    @Test
    fun `a matching protocol gets network settings`() {
        clientSends(requestNetworkSettings(PROTOCOL))

        assertContentEquals(bytes(0x8F, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), receivedPacket())
    }

    @Test
    fun `older clients are told they're out of date`() {
        clientSends(requestNetworkSettings(PROTOCOL - 1))

        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x01), receivedPacket())
    }

    @Test
    fun `newer clients are told the server is out of date, and nothing more`() {
        clientSends(requestNetworkSettings(PROTOCOL + 1))
        clientSends(requestNetworkSettings(PROTOCOL))

        assertEquals(1, sent.size)
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x02), receivedPacket())
    }

    @Test
    fun `an authenticated login gets login success and the pack list together`() {
        negotiateSettings()

        // The skin makes the batch big enough to be compressed, as real logins are.
        clientSends(login(authority.multiplayerToken(clientKey), skinData = "A".repeat(4096)))

        val player = session.player
        assertEquals("Steve", player?.displayName)
        assertEquals(XUID, player?.xuid)
        assertEquals(true, player?.verified)
        assertEquals("1.26.50", session.login?.clientInfo?.gameVersion)

        val (loginSuccess, packsInfo) = batches.decode(sent.single())
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x00), loginSuccess)
        assertEquals(0x06, packsInfo[0].toInt())
    }

    @Test
    fun `resource packs end with start game and the item registry`() {
        logIn()

        clientSends(clientCacheStatus(supported = true))
        clientSends(packResponse(2, "downloadingfinished"))
        assertEquals(0x07, receivedPacket()[0].toInt())
        assertFalse(session.isLoadingWorld)

        clientSends(packResponse(3, "resourcepackstackfinished"))
        val (startGame, registry) = batches.decode(sent.last())
        assertEquals(0x0B, startGame[0].toInt())
        assertEquals(0x0E, startGame[1].toInt()) // Entity ID 7, zigzagged
        assertContentEquals(itemRegistry.encode(), registry)
        assertTrue(session.isLoadingWorld)
        assertTrue(session.cacheSupported)
    }

    @Test
    fun `the chunk radius is what the client asks for, within kore's view distance`() {
        loadWorld()

        clientSends(requestChunkRadius(radius = 6, maxRadius = 16))
        assertContentEquals(bytes(0x46, 0x0C), receivedPacket())

        clientSends(requestChunkRadius(radius = 32, maxRadius = 32))
        assertContentEquals(bytes(0x46, 0x10), receivedPacket())
        assertEquals(8, session.chunkRadius)

        clientSends(requestChunkRadius(radius = 12, maxRadius = 4))
        assertEquals(4, session.chunkRadius)
    }

    @Test
    fun `a client that cancels during resource packs goes no further`() {
        logIn()

        clientSends(packResponse(0, "cancel"))
        clientSends(packResponse(2, "downloadingfinished"))

        assertTrue(sent.isEmpty())
        assertFalse(session.isLoadingWorld)
    }

    @Test
    fun `a login that doesn't authenticate is disconnected`() {
        negotiateSettings()

        clientSends(login(TestAuthority().multiplayerToken(clientKey)))
        clientSends(login(authority.multiplayerToken(clientKey)))

        // Disconnect (5) with reason NotAuthenticated (46, zigzagged to 92) and no custom message.
        assertContentEquals(bytes(0x05, 0x5C, 0x01), receivedPacket())
        assertEquals(1, sent.size)
        assertNull(session.player)
    }

    @Test
    fun `packets out of order are ignored`() {
        clientSends(login(authority.multiplayerToken(clientKey)))

        assertNull(session.player)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a malformed batch doesn't derail the session`() {
        negotiateSettings()

        session.receive(bytes(0x05, 0x01))
        clientSends(login(authority.multiplayerToken(clientKey)))

        assertEquals("Steve", session.player?.displayName)
    }

    private fun negotiateSettings() {
        clientSends(requestNetworkSettings(PROTOCOL))
        sent.clear()
        batches.enableCompression(256)
    }

    private fun logIn() {
        negotiateSettings()
        clientSends(login(authority.multiplayerToken(clientKey)))
        sent.clear()
    }

    private fun loadWorld() {
        logIn()
        clientSends(packResponse(2, "downloadingfinished"))
        clientSends(packResponse(3, "resourcepackstackfinished"))
        sent.clear()
    }

    private fun requestChunkRadius(radius: Int, maxRadius: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(69).writeVarInt(radius).writeByte(maxRadius))

    private fun clientCacheStatus(supported: Boolean) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(129).writeBoolean(supported))

    private fun packResponse(tag: Int, name: String) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(8).writeVarUInt(tag).writeString(name))

    private fun clientSends(packet: ByteArray) = session.receive(batches.encode(listOf(packet)))

    private fun receivedPacket() = batches.decode(sent.last()).single()

    private fun requestNetworkSettings(protocol: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(protocol))

    private fun login(token: String, skinData: String = "") =
        loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(client.private, skinData)))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
