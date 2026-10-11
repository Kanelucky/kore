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
import org.koremc.protocol.ActorDataId
import org.koremc.protocol.ActorFlag
import org.koremc.protocol.BiomeDefinitionList
import org.koremc.protocol.BlockDefinitions
import org.koremc.protocol.ContainerClose
import org.koremc.protocol.ContainerOpen
import org.koremc.protocol.FloatItem
import org.koremc.protocol.SetActorData
import org.koremc.protocol.flagsItem
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.ResourcePackStack
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
import org.koremc.registry.CreativeInventory
import org.koremc.registry.BlockDefinition
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemVersion
import org.koremc.world.BlockPos
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

// Input flags, by their numbers in PlayerAuthInput.
private const val SNEAKING = 8
private const val START_SPRINTING = 25
private const val STOP_SPRINTING = 26
private const val START_FLYING = 42

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
    private val blockDefinitions = BlockDefinitions(listOf(BlockDefinition("minecraft:white_wool_stairs", NbtMap.builder().putInt("molangVersion", 13).build())))
    private val game = GameData(world, listOf(itemRegistry, biomes), blockDefinitions, CreativeInventory(emptyList(), emptyList()))
    private val session = Session(config, game, networkId = "9876543210123456789", entityId = 7, offerKey = clientKey, authenticator) { sent += it }

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
        assertContentEquals(ResourcePackStack(baseGameVersion = "1.26.50").encode(), receivedPacket())
        assertFalse(session.isLoadingWorld)

        clientSends(packResponse(3, "resourcepackstackfinished"))
        val (jigsaws, voxelShapes, startGame, registry, biomeList) = batches.decode(sent.last())
        // The client disconnects unless these two come before StartGame.
        assertEquals(0xB9, jigsaws[0].toInt() and 0xFF)
        assertEquals(0xD1, voxelShapes[0].toInt() and 0xFF)
        assertEquals(0x0B, startGame[0].toInt())
        assertEquals(0x0E, startGame[1].toInt()) // Entity ID 7, zigzagged
        assertTrue("minecraft:white_wool_stairs" in startGame.decodeToString()) // The block definitions are in there
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
    fun `sneaking shows in the player's flags and height, at the input's tick`() {
        spawn()

        clientSends(playerAuthInput(standing, inputs = listOf(SNEAKING), tick = 101))
        assertContentEquals(playerData(ActorFlag.SNEAKING, height = 1.49f, tick = 101), receivedPacket())
        assertTrue(session.sneaking)

        sent.clear()
        clientSends(playerAuthInput(standing, inputs = listOf(SNEAKING), tick = 102)) // Still holding it
        assertTrue(sent.isEmpty())

        clientSends(playerAuthInput(standing, tick = 103))
        assertContentEquals(playerData(height = 1.8f, tick = 103), receivedPacket())
        assertFalse(session.sneaking)
    }

    @Test
    fun `sprinting starts and stops with the client's flags`() {
        spawn()

        clientSends(playerAuthInput(standing, inputs = listOf(START_SPRINTING), tick = 101))
        assertContentEquals(playerData(ActorFlag.SPRINTING, height = 1.8f, tick = 101), receivedPacket())

        sent.clear()
        clientSends(playerAuthInput(standing, tick = 102)) // Still sprinting: there's no flag held for it
        assertTrue(sent.isEmpty())
        assertTrue(session.sprinting)

        clientSends(playerAuthInput(standing, inputs = listOf(STOP_SPRINTING), tick = 103))
        assertContentEquals(playerData(height = 1.8f, tick = 103), receivedPacket())
        assertFalse(session.sprinting)
    }

    @Test
    fun `sneaking ends a sprint, except in flight`() {
        spawn()

        clientSends(playerAuthInput(standing, inputs = listOf(START_SPRINTING)))
        clientSends(playerAuthInput(standing, inputs = listOf(SNEAKING)))
        assertFalse(session.sprinting)
        assertTrue(session.sneaking)

        clientSends(playerAuthInput(standing, inputs = listOf(START_FLYING, START_SPRINTING)))
        clientSends(playerAuthInput(standing, inputs = listOf(SNEAKING))) // Descending
        assertTrue(session.sprinting)
        assertTrue(session.sneaking)
    }

    @Test
    fun `the inventory opens when the client asks, once`() {
        spawn()

        clientSends(interact(action = 6, target = 7))
        // The player's own inventory, at the block they're in: in this one-layer world, their eyes start at 0.5, -61.38, 0.5.
        assertContentEquals(ContainerOpen(0, 0xFF, BlockPos(0, -62, 0), entityId = -1).encode(), receivedPacket())

        sent.clear()
        clientSends(interact(action = 6, target = 7)) // Asked again before the answer arrived
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `closing the inventory is agreed to, and it can open again`() {
        spawn()
        clientSends(interact(action = 6, target = 7))

        clientSends(containerClose(window = 0, type = 0xFF))
        assertContentEquals(ContainerClose(0, 0).encode(), receivedPacket())

        clientSends(interact(action = 6, target = 7))
        assertEquals(0x2E, receivedPacket()[0].toInt())
    }

    @Test
    fun `chat closing the inventory needs no answer`() {
        spawn()
        clientSends(interact(action = 6, target = 7))
        sent.clear()

        clientSends(containerClose(window = 0xFF, type = 0xFF))
        assertTrue(sent.isEmpty())

        clientSends(interact(action = 6, target = 7))
        assertEquals(0x2E, receivedPacket()[0].toInt())
    }

    @Test
    fun `in game, the packet log only quietens movement`() {
        spawn()
        val packetLog = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("org.koremc.packets") as Logger
        logger.level = ch.qos.logback.classic.Level.TRACE
        logger.addAppender(packetLog)
        try {
            clientSends(playerAuthInput(standing))
            clientSends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(44).writeZero(4))) // A packet Kore doesn't handle
        } finally {
            logger.detachAppender(packetLog)
            logger.level = null
        }

        assertEquals(listOf("TRACE", "DEBUG"), packetLog.list.map { it.level.toString() })
    }

    @Test
    fun `item stack requests get an answer each`() {
        spawn()

        // Request -1 picks the first creative item. This player isn't in creative mode, so it's turned down.
        val request = ByteBufUtil.getBytes(
            Unpooled.buffer().writeVarUInt(147).writeVarUInt(1).writeVarInt(-1)
                .writeVarUInt(1).writeVarUInt(12).writeByte(14).writeVarUInt(1).writeByte(1)
                .writeVarUInt(0).writeIntLE(0),
        )
        clientSends(request)

        // ItemStackResponse: one response, rejected (1), for request -1 (zigzagged to 1), with no containers.
        assertContentEquals(bytes(0x94, 0x01, 0x01, 0x01, 0x01, 0x00), receivedPacket())
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

    /** A PlayerAuthInput with the player at [position], the input flags numbered in [inputs] set, and nothing else going on. */
    private fun playerAuthInput(position: Vec3, inputs: List<Int> = emptyList(), tick: Long = 100) = ByteBufUtil.getBytes(
        Unpooled.buffer()
            .writeVarUInt(144)
            .writeZero(8).writeFloatLE(position.x).writeFloatLE(position.y).writeFloatLE(position.z)
            .writeZero(12).writeVarUInt(inputs.size).apply { inputs.forEach { writeVarInt(it) } }
            .writeVarUInt(1).writeVarUInt(0).writeVarInt(1).writeZero(8)
            .writeVarULong(tick).writeZero(12).writeZero(5).writeZero(28),
    )

    /** The player's actor data as Kore sends it when they start or stop sprinting or sneaking. */
    private fun playerData(vararg flags: ActorFlag, height: Float, tick: Long): ByteArray {
        val always = setOf(ActorFlag.BREATHING, ActorFlag.HAS_GRAVITY, ActorFlag.HAS_COLLISION, ActorFlag.CAN_CLIMB)
        return SetActorData(7, listOf(flagsItem(always + flags), FloatItem(ActorDataId.HEIGHT, height)), tick).encode()
    }

    private val standing = Vec3(0.5f, -61.38f, 0.5f)

    private fun interact(action: Int, target: Long) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(33).writeByte(action).writeVarULong(target).writeBoolean(false))

    private fun containerClose(window: Int, type: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(47).writeByte(window).writeByte(type).writeBoolean(false))

    private fun setLocalPlayerAsInitialised(runtimeId: Long) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(113).writeVarULong(runtimeId))

    private fun requestNetworkSettings(protocol: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(protocol))

    private fun login(token: String, skinData: String = "") =
        loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(client.private, skinData)))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
