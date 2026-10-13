package org.koremc

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.ecKeyPair
import org.koremc.protocol.ActorDataId
import org.koremc.protocol.ActorFlag
import org.koremc.protocol.Animate
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.Disconnect
import org.koremc.protocol.FloatItem
import org.koremc.protocol.GameMode
import org.koremc.protocol.LevelSoundEvent
import org.koremc.protocol.MovePlayer
import org.koremc.protocol.RemoveActor
import org.koremc.protocol.SetActorData
import org.koremc.protocol.ShowEquipment
import org.koremc.protocol.Text
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.encode
import org.koremc.protocol.flagsItem
import org.koremc.protocol.loginPacket
import org.koremc.protocol.readString
import org.koremc.protocol.readVarUInt
import org.koremc.protocol.readVarULong
import org.koremc.protocol.readVec3
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.writeString
import org.koremc.protocol.writeVarInt
import org.koremc.protocol.writeVarUInt
import org.koremc.protocol.writeVarULong
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeInventory
import org.koremc.world.Drops
import org.koremc.world.FlatWorld
import org.koremc.world.Vec3
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROTOCOL = 2193

// Packet IDs, for telling apart what a client got.
private const val ADD_PLAYER = 12
private const val REMOVE_ACTOR = 14
private const val PLAYER_LIST = 63

/** The input flag set on every tick a player sneaks. */
private const val SNEAKING = 8

class PlayersTest {
    private val authority = TestAuthority()
    private val authenticator = LoginAuthenticator(MultiplayerTokenVerifier(keys = { authority.keys }), onlineMode = true)
    private var config = ServerConfig(protocol = PROTOCOL, viewDistance = 8, gameMode = GameMode.SURVIVAL)

    private val stone = BlockState("minecraft:stone", NbtMap.EMPTY, version = 18168865)
    private val palette = BlockPalette(listOf(BlockState(AIR, NbtMap.EMPTY, version = 18168865), stone))
    private val world = FlatWorld(palette, listOf(stone))
    private val creative = CreativeInventory(emptyList(), emptyList())
    private val game = GameData(world, emptyList(), org.koremc.protocol.BlockDefinitions(emptyList()), creative, emptyMap(), Drops(emptyList(), creative, palette))

    private val clients = mutableListOf<Client>()

    /** Tasks the sessions have asked to run later. Tests run them. */
    private val scheduled = mutableListOf<() -> Unit>()

    /** One player's client, with its own session on the shared server. */
    private inner class Client(val entityId: Long, val name: String, xuid: String) {
        private val key = ecKeyPair()
        private val batches = BatchCodec()
        private val sent = mutableListOf<ByteArray>()
        val session: Session = Session(
            config, game, networkId = "network-$entityId", entityId, key.public as ECPublicKey, authenticator,
            viewers = { clients.map { it.session } },
            schedule = { _, task -> scheduled += task },
        ) { sent += it }

        /** Every packet the client got as it joined. */
        val joining: List<ByteArray>

        init {
            clients += this
            sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(PROTOCOL)))
            batches.enableCompression(256)
            sent.clear() // The network settings, from before compression
            val token = authority.multiplayerToken(key.public as ECPublicKey, xuid = xuid, displayName = name)
            sends(loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(key.private))))
            sends(packResponse(2, "downloadingfinished"))
            sends(packResponse(3, "resourcepackstackfinished"))
            sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(69).writeVarInt(2).writeByte(2)))
            // The loading screen closes as the player is initialised, in one batch.
            session.receive(
                batches.encode(
                    listOf(
                        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(312).writeVarInt(2).writeBoolean(false)),
                        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(113).writeVarULong(entityId)),
                    ),
                ),
            )
            joining = received()
        }

        fun sends(packet: ByteArray) = session.receive(batches.encode(listOf(packet)))

        /** Every packet the client got since the last look. */
        fun received(): List<ByteArray> = sent.flatMap { batches.decode(it) }.also { sent.clear() }

        /** Moves the player's eyes to [position], looking the way [pitch] and [yaw] say, with the input flags numbered in [inputs]. */
        fun moves(position: Vec3, pitch: Float = 0f, yaw: Float = 0f, inputs: List<Int> = emptyList()) = sends(
            ByteBufUtil.getBytes(
                Unpooled.buffer()
                    .writeVarUInt(144)
                    .writeFloatLE(pitch).writeFloatLE(yaw).writeFloatLE(position.x).writeFloatLE(position.y).writeFloatLE(position.z)
                    .writeZero(8).writeFloatLE(yaw) // Move vector, then head yaw
                    .writeVarUInt(inputs.size).apply { inputs.forEach { writeVarInt(it) } }
                    .writeVarUInt(1).writeVarUInt(0).writeVarInt(1).writeZero(8)
                    .writeVarULong(100).writeZero(12).writeZero(5).writeZero(28),
            ),
        )

        private fun packResponse(tag: Int, name: String) =
            ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(8).writeVarUInt(tag).writeString(name))
    }

    /** A tick of showing players to each other, as the server runs it. */
    private fun tick() = clients.forEach { it.session.syncPlayers() }

    @Test
    fun `a full server turns away anyone else who joins, telling them so, until someone leaves`() {
        config = config.copy(maxPlayers = 1)
        val steve = Client(1, "Steve", "1111")

        val alex = Client(2, "Alex", "2222")
        assertFalse(alex.session.isInGame)
        assertContentEquals(Disconnect(Disconnect.Reason.SERVER_FULL, "disconnectionScreen.serverFull").encode(), alex.joining.single())

        steve.session.leave()
        assertTrue(Client(3, "Alex", "2222").session.isInGame)
    }

    @Test
    fun `players in the game are on each other's lists, and appear to those who have their chunk`() {
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        // A moment after each loading screen closes, everyone in the game hears who joined.
        assertTrue(steve.received().isEmpty())
        scheduled.forEach { it() }
        assertEquals(listOf(joined("Steve"), joined("Alex")).map { it.toList() }, steve.received().map { it.toList() })
        assertEquals(listOf(joined("Steve"), joined("Alex")).map { it.toList() }, alex.received().map { it.toList() })

        tick()

        // Each list has both players, the client's own included, before the other appears.
        val (list, added) = steve.received()
        assertEquals(listOf(PLAYER_LIST, ADD_PLAYER), listOf(idOf(list), idOf(added)))
        assertEquals(listOf(PLAYER_LIST, ADD_PLAYER), alex.received().map(::idOf))
        // Alex stands at spawn: their feet on the stone, where moves would give their eyes.
        val feet = positionIn(added)
        assertEquals(-63f, feet.y, 0.001f)
        tick()
        assertTrue(steve.received().isEmpty()) // Nothing changed
    }

    @Test
    fun `players see each other move, sneak, change what they hold and swing`() {
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        tick()
        steve.received()

        alex.moves(Vec3(2.5f, -61.38f, 3.5f), pitch = 10f, yaw = 90f, inputs = listOf(SNEAKING))
        alex.sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(31).writeVarULong(2).writeZero(8).writeByte(3).writeByte(3).writeByte(0)))
        tick()

        val sneaking = setOf(ActorFlag.BREATHING, ActorFlag.HAS_GRAVITY, ActorFlag.HAS_COLLISION, ActorFlag.CAN_CLIMB, ActorFlag.SHOW_NAME, ActorFlag.ALWAYS_SHOW_NAME, ActorFlag.SNEAKING)
        val expected = listOf(
            MovePlayer(2, Vec3(2.5f, -61.38f, 3.5f), 10f, 90f, 90f, onGround = false),
            SetActorData(2, listOf(flagsItem(sneaking), FloatItem(ActorDataId.HEIGHT, 1.49f))),
            ShowEquipment(2, stack = null, slot = 3),
        )
        assertEquals(expected.map { it.encode().toList() }, steve.received().map { it.toList() })

        alex.received()

        // Swinging at nothing, as when mining.
        alex.sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(44).writeByte(1).writeVarULong(2).writeFloatLE(0f).writeBoolean(true).writeString("mine")))
        assertContentEquals(Animate(Animate.SWING_ARM, 2, swingSource = "mine").encode(), steve.received().single())
        assertTrue(alex.received().isEmpty()) // Their own client already showed it
    }

    @Test
    fun `players go from view when they walk out of it, and from the list when they leave`() {
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        tick()
        steve.received()

        // Steve sees five chunks across, so 100 blocks away is out of view.
        alex.moves(Vec3(100.5f, -61.38f, 0.5f))
        tick()
        assertContentEquals(RemoveActor(2).encode(), steve.received().single())

        // Alex's session ends, and Steve hears Alex left, then sees Alex go from the list.
        clients.remove(alex)
        alex.session.leave()
        assertContentEquals(
            Text(Text.TRANSLATION, "\u00a7e%multiplayer.player.left", parameters = listOf("Alex"), needsTranslation = true).encode(),
            steve.received().single(),
        )
        tick()
        assertEquals(listOf(PLAYER_LIST), steve.received().map(::idOf))
    }

    @Test
    fun `chat goes to everyone in the game, under the name the player logged in with`() {
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        steve.received()

        alex.sends(Text(Text.CHAT, "  hello  ", sourceName = "Notch").encode())

        // Alex sees it too: the client only shows its own messages once the server sends them back.
        val expected = Text(Text.CHAT, "hello", sourceName = "Alex", xuid = "2222").encode()
        assertContentEquals(expected, steve.received().single())
        assertContentEquals(expected, alex.received().single())

        alex.sends(Text(Text.CHAT, "   ").encode())
        assertTrue(steve.received().isEmpty())
    }

    private fun joined(name: String) = Text(Text.TRANSLATION, "\u00a7e%multiplayer.player.joined", parameters = listOf(name), needsTranslation = true).encode()

    @Test
    fun `the sounds a player makes, like the block they're mining being hit, reach everyone around, them too`() {
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        steve.received()
        alex.received()
        val near = Vec3(world.spawn.x + 1.5f, world.spawn.y - 0.5f, world.spawn.z + 0.5f)

        alex.sends(LevelSoundEvent("hit", near, 0x1234, entityType = "", entityId = 2).encode())

        val heard = LevelSoundEvent("hit", near, 0x1234, entityType = "", entityId = 2).encode()
        assertContentEquals(heard, steve.received().single())
        assertContentEquals(heard, alex.received().single())

        // One far beyond what the player reaches goes nowhere.
        alex.sends(LevelSoundEvent("hit", Vec3(near.x + 100f, near.y, near.z), 0x1234).encode())
        assertTrue(steve.received().isEmpty())
    }

    private fun idOf(packet: ByteArray) = Unpooled.wrappedBuffer(packet).readVarUInt() and 0x3FF

    /** Where an AddPlayer puts its player: after the ID, UUID, name, runtime ID and platform chat ID. */
    private fun positionIn(addPlayer: ByteArray): Vec3 {
        val buffer = Unpooled.wrappedBuffer(addPlayer)
        buffer.readVarUInt()
        buffer.skipBytes(16)
        buffer.readString()
        buffer.readVarULong()
        buffer.readString()
        return buffer.readVec3()
    }
}
