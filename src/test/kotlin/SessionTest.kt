package org.koremc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.XUID
import org.koremc.auth.ecKeyPair
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.BiomeDefinitionList
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.encode
import org.koremc.protocol.loginPacket
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.writeString
import org.koremc.protocol.writeVarInt
import org.koremc.protocol.writeVarUInt
import org.koremc.protocol.writeVarULong
import org.koremc.registry.BiomeDefinition
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemVersion
import org.koremc.world.ChunkPos
import org.koremc.world.FlatWorld
import org.koremc.world.Vec3
import org.koremc.world.chunksAround
import org.slf4j.LoggerFactory
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
    private val biomes = BiomeDefinitionList(listOf(BiomeDefinition("minecraft:plains", null, 0.8f, 0.4f, 0f, 0.125f, 0.05f, 0, rain = true, listOf("plains"))))
    private val world = FlatWorld(listOf(BlockState("minecraft:stone", NbtMap.EMPTY, version = 18168865)))
    private val sent = mutableListOf<ByteArray>()
    private val session = Session(config, listOf(itemRegistry, biomes), world, networkId = "9876543210123456789", entityId = 7, offerKey = clientKey, authenticator) { sent += it }

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
    fun `resource packs end with start game, the item registry and the biomes`() {
        logIn()

        clientSends(clientCacheStatus(supported = true))
        clientSends(packResponse(2, "downloadingfinished"))
        assertEquals(0x07, receivedPacket()[0].toInt())
        assertFalse(session.isLoadingWorld)

        clientSends(packResponse(3, "resourcepackstackfinished"))
        val (jigsaws, voxelShapes, startGame, registry, biomeList) = batches.decode(sent.last())
        // The client disconnects unless these two come before StartGame.
        assertEquals(0xB9, jigsaws[0].toInt() and 0xFF)
        assertEquals(0xD1, voxelShapes[0].toInt() and 0xFF)
        assertEquals(0x0B, startGame[0].toInt())
        assertEquals(0x0E, startGame[1].toInt()) // Entity ID 7, zigzagged
        assertContentEquals(itemRegistry.encode(), registry)
        assertContentEquals(biomes.encode(), biomeList)
        assertTrue(session.isLoadingWorld)
        assertTrue(session.cacheSupported)
    }

    @Test
    fun `the chunk radius is what the client asks for, within kore's view distance`() {
        loadWorld()

        clientSends(requestChunkRadius(radius = 6, maxRadius = 16))
        assertContentEquals(bytes(0x46, 0x0C), receivedPackets().first())

        clientSends(requestChunkRadius(radius = 32, maxRadius = 32))
        assertContentEquals(bytes(0x46, 0x10), receivedPackets().first())
        assertEquals(8, session.chunkRadius)

        clientSends(requestChunkRadius(radius = 12, maxRadius = 4))
        assertEquals(4, session.chunkRadius)
    }

    @Test
    fun `chunks in view go out nearest first, then the player may spawn`() {
        loadWorld()

        clientSends(requestChunkRadius(radius = 2, maxRadius = 2))
        val packets = receivedPackets()

        // ChunkRadiusUpdated, NetworkChunkPublisherUpdate, the 13 chunks within two of the spawn, then PlayStatus.
        assertEquals(listOf(0x46, 0x79) + List(13) { 0x3A } + 0x02, packets.map { it[0].toInt() })
        assertContentEquals(bytes(0x3A, 0x00, 0x00), packets[2].copyOf(3)) // Chunk 0, 0 first
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x03), packets.last()) // PLAYER_SPAWN
        assertFalse(session.isInGame)
    }

    @Test
    fun `a wider view sends only the chunks the client doesn't have`() {
        loadWorld()
        clientSends(requestChunkRadius(radius = 2, maxRadius = 2))
        sent.clear()

        clientSends(requestChunkRadius(radius = 3, maxRadius = 3))

        // 29 chunks are in view now, and 13 of them went out already. The player doesn't spawn twice.
        assertEquals(listOf(0x46, 0x79) + List(16) { 0x3A }, receivedPackets().map { it[0].toInt() })
    }

    @Test
    fun `the player is in the game once the client initialises its own runtime ID`() {
        loadWorld()
        clientSends(requestChunkRadius(radius = 2, maxRadius = 2))

        clientSends(setLocalPlayerAsInitialised(runtimeId = 8))
        assertFalse(session.isInGame)

        clientSends(setLocalPlayerAsInitialised(runtimeId = 7))
        assertTrue(session.isInGame)

        // Then the client learns about its own player: its actor data, attributes and abilities.
        assertEquals(listOf(0x27, 0x1D, 0xBB), batches.decode(sent.last()).map { it[0].toInt() and 0xFF })
    }

    @Test
    fun `every batch in and out goes to the packet log, and logins only by size`() {
        val packetLog = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("org.koremc.packets") as Logger
        logger.addAppender(packetLog)
        try {
            loadWorld()
            clientSends(requestChunkRadius(radius = 2, maxRadius = 2))
        } finally {
            logger.detachAppender(packetLog)
        }

        val lines = packetLog.list.map { it.formattedMessage.substringBefore(" (") }
        assertTrue(lines.any { it.startsWith("<- network 9876543210123456789: Login(protocol=2193, ") })
        assertEquals(
            listOf(
                "<- Steve: RequestChunkRadius(radius=2, maxRadius=2)",
                "-> Steve: ChunkRadiusUpdated, NetworkChunkPublisherUpdate",
                "-> Steve: LevelChunk x13",
                "-> Steve: PlayStatus",
            ),
            lines.takeLast(4),
        )
    }

    @Test
    fun `moving within a chunk only updates where the player is`() {
        spawn()

        clientSends(playerAuthInput(Vec3(8.5f, -61.38f, 8.5f)))

        assertEquals(Vec3(8.5f, -61.38f, 8.5f), session.position)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `straying two chunks moves the view and sends the chunks that came into it`() {
        spawn()

        clientSends(playerAuthInput(Vec3(16.5f, -61.38f, 8.5f)))
        assertTrue(sent.isEmpty())

        clientSends(playerAuthInput(Vec3(32.5f, -61.38f, 8.5f)))
        val packets = receivedPackets()

        val cameIntoView = chunksAround(ChunkPos(2, 0), 2) - chunksAround(ChunkPos(0, 0), 2).toSet()
        // The view now centres on block 32, -62, 8, two chunks out (32 blocks), and has no saved chunks.
        assertContentEquals(bytes(0x79, 0x40, 0x7B, 0x10, 0x20, 0x00, 0x00, 0x00, 0x00), packets.first())
        assertEquals(List(cameIntoView.size) { 0x3A }, packets.drop(1).map { it[0].toInt() })
        assertEquals(8, cameIntoView.size)
    }

    @Test
    fun `stepping back and forth over a chunk border sends nothing`() {
        spawn()

        repeat(3) {
            clientSends(playerAuthInput(Vec3(16.5f, -61.38f, 16.5f))) // Diagonally into chunk 1, 1
            clientSends(playerAuthInput(Vec3(15.5f, -61.38f, 15.5f))) // And back into chunk 0, 0
        }

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `once the view has moved, stepping back doesn't move it again`() {
        spawn()
        clientSends(playerAuthInput(Vec3(32.5f, -61.38f, 8.5f)))
        sent.clear()

        clientSends(playerAuthInput(Vec3(31.5f, -61.38f, 8.5f))) // Back into chunk 1, 0, one from the new centre
        clientSends(playerAuthInput(Vec3(32.5f, -61.38f, 8.5f)))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `impossible positions are ignored`() {
        spawn()
        val start = session.position

        clientSends(playerAuthInput(Vec3(Float.NaN, 0f, 0f)))
        clientSends(playerAuthInput(Vec3(4e7f, 0f, 0f)))

        assertEquals(start, session.position)
        assertTrue(sent.isEmpty())
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

    /** Every packet sent since the last look, in order, across however many batches carried them. */
    private fun receivedPackets() = sent.flatMap { batches.decode(it) }.also { sent.clear() }

    /** Spawns the player into a view two chunks wide, and forgets what was sent on the way. */
    private fun spawn() {
        loadWorld()
        clientSends(requestChunkRadius(radius = 2, maxRadius = 2))
        clientSends(setLocalPlayerAsInitialised(runtimeId = 7))
        sent.clear()
    }

    /** A PlayerAuthInput with the player at [position], and nothing else going on. */
    private fun playerAuthInput(position: Vec3) = ByteBufUtil.getBytes(
        Unpooled.buffer()
            .writeVarUInt(144)
            .writeZero(8).writeFloatLE(position.x).writeFloatLE(position.y).writeFloatLE(position.z)
            .writeZero(12).writeVarUInt(0).writeVarUInt(1).writeVarUInt(0).writeVarInt(1).writeZero(8)
            .writeVarULong(100).writeZero(12).writeZero(5).writeZero(28),
    )

    private fun setLocalPlayerAsInitialised(runtimeId: Long) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(113).writeVarULong(runtimeId))

    private fun requestNetworkSettings(protocol: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(protocol))

    private fun login(token: String, skinData: String = "") =
        loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(client.private, skinData)))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
