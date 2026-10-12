package org.koremc

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.ecKeyPair
import org.koremc.inventory.SlotStack
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.BlockDefinitions
import org.koremc.protocol.BlockEvent
import org.koremc.protocol.ContainerClose
import org.koremc.protocol.ContainerName
import org.koremc.protocol.ContainerOpen
import org.koremc.protocol.ContainerType
import org.koremc.protocol.GameMode
import org.koremc.protocol.InventoryContent
import org.koremc.protocol.InventorySlot
import org.koremc.protocol.LevelSoundEvent
import org.koremc.protocol.UI_WINDOW
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.encode
import org.koremc.protocol.loginPacket
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.writeBlockPos
import org.koremc.protocol.writeByteArray
import org.koremc.protocol.writeString
import org.koremc.protocol.writeVarInt
import org.koremc.protocol.writeVarUInt
import org.koremc.protocol.writeVarULong
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeCategory
import org.koremc.registry.CreativeGroup
import org.koremc.registry.CreativeInventory
import org.koremc.registry.CreativeItem
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.ItemVersion
import org.koremc.world.BlockPos
import org.koremc.world.Drops
import org.koremc.world.FlatWorld
import org.koremc.world.centre
import org.koremc.world.pairChest
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROTOCOL = 2193

class ContainersTest {
    private val authority = TestAuthority()
    private val authenticator = LoginAuthenticator(MultiplayerTokenVerifier(keys = { authority.keys }), onlineMode = true)
    private var config = ServerConfig(protocol = PROTOCOL, viewDistance = 8, gameMode = GameMode.CREATIVE)

    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val stone = state("minecraft:stone")
    private val chest = state("minecraft:chest", "minecraft:cardinal_direction" to "south")
    private val enderChest = state("minecraft:ender_chest", "minecraft:cardinal_direction" to "south")
    private val barrel = state("minecraft:barrel", "facing_direction" to 1, "open_bit" to 0.toByte())
    private val openBarrel = state("minecraft:barrel", "facing_direction" to 1, "open_bit" to 1.toByte())
    private val shulkerBox = state("minecraft:white_shulker_box")
    private val craftingTable = state("minecraft:crafting_table")
    private val palette = BlockPalette(listOf(state(AIR), stone, chest, enderChest, barrel, openBarrel, shulkerBox, craftingTable))
    private val world = FlatWorld(palette, listOf(stone))

    private val stoneItem = ItemDefinition("minecraft:stone", 1, componentBased = false, ItemVersion.NONE)
    private val shulkerItem = ItemDefinition("minecraft:white_shulker_box", 218, componentBased = false, ItemVersion.NONE, maxStackSize = 1)
    private val items = listOf(stoneItem, shulkerItem)
    private val creative = CreativeInventory(
        listOf(CreativeGroup(CreativeCategory.CONSTRUCTION, "", icon = null)),
        listOf(CreativeItem(ItemStack(stoneItem, block = stone), group = 0), CreativeItem(ItemStack(shulkerItem, block = shulkerBox), group = 0)),
    )
    private val game = GameData(world, emptyList(), BlockDefinitions(emptyList()), creative, emptyMap(), Drops(items, creative, palette), ItemStacks(items, creative, palette))

    private val clients = mutableListOf<Client>()

    /** One player's client, with its own session on the shared server. */
    private inner class Client(val entityId: Long, name: String, xuid: String) {
        private val key = ecKeyPair()
        private val batches = BatchCodec()
        private val sent = mutableListOf<ByteArray>()
        val session: Session = Session(
            config, game, networkId = "network-$entityId", entityId, key.public as ECPublicKey, authenticator,
            viewers = { clients.map { it.session } },
            schedule = { _, _ -> },
        ) { sent += it }

        init {
            clients += this
            sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(PROTOCOL)))
            batches.enableCompression(256)
            val token = authority.multiplayerToken(key.public as ECPublicKey, xuid = xuid, displayName = name)
            sends(loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(key.private))))
            sends(packResponse(2, "downloadingfinished"))
            sends(packResponse(3, "resourcepackstackfinished"))
            sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(69).writeVarInt(2).writeByte(2)))
            sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(113).writeVarULong(entityId)))
            sent.clear()
        }

        fun sends(packet: ByteArray) = session.receive(batches.encode(listOf(packet)))

        /** Every packet the client got since the last look. */
        fun received(): List<ByteArray> = sent.flatMap { batches.decode(it) }.also { sent.clear() }

        /** Clicks the top of the block at [pos], holding the item numbered [held] in the hotbar's first slot, or nothing. */
        fun clicks(pos: BlockPos, held: Int = 0, block: Int = 0) = sends(
            ByteBufUtil.getBytes(
                Unpooled.buffer().writeVarUInt(30).writeVarInt(0).writeBoolean(false).writeVarUInt(2).writeVarUInt(0)
                    .writeVarInt(0).writeByte(1).writeBlockPos(pos).writeByte(1).writeVarInt(0).writeByte(0)
                    .item(held, block)
                    .writeFloatLE(0.5f).writeFloatLE(-61.38f).writeFloatLE(0.5f)
                    .writeFloatLE(0.5f).writeFloatLE(1f).writeFloatLE(0.5f)
                    .writeVarUInt(world.blockAt(pos)).writeBoolean(true).writeByte(0),
            ),
        )

        /** Moves [count] items from [from] to [to], each a container name, slot and the stack ID the client expects there. */
        fun moves(count: Int, from: Triple<Int, Int, Int>, to: Triple<Int, Int, Int>, requestId: Int = -1) = sends(
            ByteBufUtil.getBytes(
                Unpooled.buffer().writeVarUInt(147).writeVarUInt(1).writeVarInt(requestId).writeVarUInt(1)
                    .writeVarUInt(0).writeByte(0).writeByte(count).slot(from).slot(to)
                    .writeVarUInt(0).writeIntLE(0),
            ),
        )

        fun closes(window: Int) = sends(ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(47).writeByte(window).writeByte(0).writeBoolean(false)))

        /** Breaks the block at [pos], as a creative player does. */
        fun breaks(pos: BlockPos) = sends(
            ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(36).writeVarULong(entityId).writeVarInt(13).writeBlockPos(pos).writeBlockPos(BlockPos(0, 0, 0)).writeVarInt(1)),
        )

        private fun ByteBuf.item(id: Int, block: Int): ByteBuf =
            writeShortLE(id).writeShortLE(if (id == 0) 0 else 1).writeVarUInt(0).writeBoolean(false).writeVarUInt(block).writeByteArray(ByteArray(10))

        private fun ByteBuf.slot(slot: Triple<Int, Int, Int>): ByteBuf = writeByte(slot.first).writeBoolean(false).writeByte(slot.second).writeIntLE(slot.third)

        private fun packResponse(tag: Int, name: String) =
            ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(8).writeVarUInt(tag).writeString(name))
    }

    private val here = BlockPos(2, -63, 0)

    private fun List<ByteArray>.has(packet: ByteArray) = any { it.contentEquals(packet) }

    /** The window a chest's 27 slots show, with [stack] in the first. */
    private fun chestWith(window: Int, stack: Pair<ItemStack, Int>?, size: Int = 27) = InventoryContent(window, listOf(stack) + List(size - 1) { null }).encode()

    @Test
    fun `a chest opens with what it holds, and its lid stays open until the last player looking in closes it`() {
        world[here] = chest
        world.containerAt(here)[0] = SlotStack(ItemStack(stoneItem, 5, block = stone), 900)
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")

        steve.clicks(here)
        val opened = steve.received()
        assertTrue(opened.has(ContainerOpen(1, ContainerType.CONTAINER, here, entityId = -1).encode()))
        assertTrue(opened.has(chestWith(1, ItemStack(stoneItem, 5, block = stone) to 900)))
        val lidOpens = BlockEvent(here, BlockEvent.CHANGE_STATE, 1).encode()
        assertTrue(opened.has(lidOpens))
        assertTrue(alex.received().has(LevelSoundEvent("chest.open", here.centre, chest.networkId).encode()))

        alex.clicks(here)
        assertTrue(alex.received().has(ContainerOpen(1, ContainerType.CONTAINER, here, entityId = -1).encode()))
        assertTrue(steve.received().none { it.contentEquals(lidOpens) }) // It was open already

        val lidCloses = BlockEvent(here, BlockEvent.CHANGE_STATE, 0).encode()
        steve.closes(1)
        assertTrue(steve.received().has(ContainerClose(1, ContainerType.CONTAINER).encode()))
        assertTrue(alex.received().none { it.contentEquals(lidCloses) })
        alex.closes(1)
        assertTrue(steve.received().has(lidCloses))
    }

    @Test
    fun `items moved in or out of a chest show to everyone looking into it`() {
        world[here] = chest
        world.containerAt(here)[0] = SlotStack(ItemStack(stoneItem, 5, block = stone), 900)
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        steve.clicks(here)
        alex.clicks(here)
        alex.received()

        steve.moves(5, from = Triple(ContainerName.LEVEL_ENTITY, 0, 900), to = Triple(ContainerName.HOTBAR, 0, 0))

        assertNull(world.containerAt(here)[0])
        assertTrue(alex.received().has(chestWith(1, null)))
    }

    @Test
    fun `a double chest is one window, the chest on the left first`() {
        val right = here.copy(x = 3)
        world[here] = chest
        world[right] = chest
        world.pairChest(right)
        world.containerAt(here)[0] = SlotStack(ItemStack(stoneItem, block = stone), 900)
        val steve = Client(1, "Steve", "1111")

        // Facing the chests' fronts, which face south, west is on the left.
        steve.clicks(right)

        assertTrue(steve.received().has(chestWith(1, ItemStack(stoneItem, block = stone) to 900, size = 54)))
    }

    @Test
    fun `a solid block on a chest keeps it shut`() {
        world[here] = chest
        world[here.copy(y = -62)] = stone
        val steve = Client(1, "Steve", "1111")

        steve.clicks(here)

        assertTrue(steve.received().none { it.contentEquals(ContainerOpen(1, ContainerType.CONTAINER, here, entityId = -1).encode()) })
    }

    @Test
    fun `a broken chest drops what it held, and closes for whoever had it open`() {
        world[here] = chest
        world.containerAt(here)[4] = SlotStack(ItemStack(stoneItem, 5, block = stone), 900)
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        steve.clicks(here)

        alex.breaks(here)

        assertEquals(listOf(5), world.items.map { it.stack.count })
        assertTrue(steve.received().has(ContainerClose(1, ContainerType.CONTAINER, serverInitiated = true).encode()))
    }

    @Test
    fun `a shulker box keeps what it held in its item, and holds it again once placed`() {
        world[here] = shulkerBox
        world.containerAt(here)[3] = SlotStack(ItemStack(stoneItem, 7, block = stone), 900)
        val steve = Client(1, "Steve", "1111")

        steve.breaks(here)
        val dropped = world.items.single().stack
        assertEquals(shulkerItem, dropped.item)
        val held = dropped.nbt.getList("Items", NbtType.COMPOUND).single()
        assertEquals(listOf("minecraft:stone", 7.toByte(), 3.toByte()), listOf(held.getString("Name"), held.getByte("Count"), held.getByte("Slot")))

        steve.session.give(dropped)
        steve.clicks(BlockPos(4, -64, 0), held = shulkerItem.runtimeId, block = shulkerBox.networkId)
        val placed = BlockPos(4, -63, 0)
        assertEquals(shulkerBox, world.stateAt(placed))
        assertEquals(ItemStack(stoneItem, 7, block = stone), world.containerAt(placed)[3]?.stack)
    }

    @Test
    fun `a barrel opens by its state, and shuts again`() {
        world[here] = barrel
        val steve = Client(1, "Steve", "1111")

        steve.clicks(here)
        assertEquals(openBarrel, world.stateAt(here))
        steve.closes(1)
        assertEquals(barrel, world.stateAt(here))
    }

    @Test
    fun `an ender chest holds each player's own items`() {
        world[here] = enderChest
        val steve = Client(1, "Steve", "1111")
        val alex = Client(2, "Alex", "2222")
        steve.session.give(ItemStack(stoneItem, 5, block = stone)) // The first stack the world numbers
        steve.clicks(here)

        steve.moves(5, from = Triple(ContainerName.HOTBAR, 0, 1), to = Triple(ContainerName.LEVEL_ENTITY, 2, 0))
        alex.clicks(here)

        assertTrue(alex.received().has(chestWith(1, null)))
    }

    @Test
    fun `a crafting table opens its grid, and what's left in it goes back among the player's items when it closes`() {
        world[here] = craftingTable
        val steve = Client(1, "Steve", "1111")
        steve.session.give(ItemStack(stoneItem, 5, block = stone))

        // Before the table opens, there's only the inventory's grid.
        steve.moves(5, from = Triple(ContainerName.HOTBAR, 0, 1), to = Triple(ContainerName.CRAFTING_INPUT, 32, 0))
        steve.clicks(here)
        assertTrue(steve.received().has(ContainerOpen(1, ContainerType.WORKBENCH, here, entityId = -1).encode()))
        steve.moves(5, from = Triple(ContainerName.HOTBAR, 0, 1), to = Triple(ContainerName.CRAFTING_INPUT, 32, 0), requestId = -3)
        steve.received()

        steve.closes(1)
        val closed = steve.received()
        assertTrue(closed.has(InventorySlot(32, null, 0, UI_WINDOW).encode()))
        assertTrue(closed.any { it.contentEquals(InventorySlot(0, ItemStack(stoneItem, 5, block = stone), 2).encode()) })
    }
}
