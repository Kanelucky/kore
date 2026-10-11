package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.world.BlockPos
import org.koremc.world.Vec3
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BlocksTest {
    private val clicked = BlockPos(2, -60, -3)
    private val click = Vec3(0.5f, 1f, 0.25f)

    @Test
    fun `placing a block is a transaction using the held item on a block`() {
        val packet = packet(30) {
            writeVarInt(0).writeBoolean(false) // No legacy request
            writeVarUInt(2) // Using an item
            writeVarUInt(0) // No slot changes
            itemUse(action = 0, face = 1)
        }

        val expected = ItemUse(ItemUseAction.PLACE, clicked, face = 1, hotbarSlot = 4, heldItem = 5, click, clickedBlock = GRASS, predictedToWork = true)
        assertEquals(InventoryTransaction(2, actions = emptyList(), expected), decodeClientPacket(packet))
    }

    @Test
    fun `a legacy request is read past, and the slot changes the client predicts are read`() {
        val packet = packet(30) {
            writeVarInt(-2).writeBoolean(true).writeVarUInt(1).writeByte(28).writeByteArray(bytes(4)) // Resend hotbar slot 4
            writeVarUInt(2)
            writeVarUInt(1)
            writeVarUInt(0).writeBoolean(true).writeByte(0).writeBoolean(false).writeVarUInt(4) // The inventory's slot 4
            item(id = 5, stackId = 3)
            item(id = 0, stackId = null)
            itemUse(action = 0, face = 5)
        }

        val transaction = decodeClientPacket(packet) as InventoryTransaction
        assertEquals(listOf(InventoryAction(0, windowId = 0, slot = 4, ClientItem(5, 1, stackId = 3), ClientItem(0, 0, stackId = null))), transaction.actions)
        assertEquals(5, transaction.use?.face)
    }

    @Test
    fun `pressing Q is a normal transaction, of items leaving a slot and going into the world`() {
        val packet = packet(30) {
            writeVarInt(0).writeBoolean(false)
            writeVarUInt(0) // Normal
            writeVarUInt(2)
            writeVarUInt(2).writeBoolean(false).writeBoolean(true).writeVarUInt(0).writeVarUInt(0) // The world, dropping
            item(id = 0, stackId = null)
            item(id = 5, stackId = null)
            writeVarUInt(0).writeBoolean(true).writeByte(0).writeBoolean(false).writeVarUInt(4) // The inventory's slot 4
            item(id = 5, stackId = 3)
            item(id = 0, stackId = null)
        }

        val expected = listOf(
            InventoryAction(InventoryAction.WORLD, windowId = null, InventoryAction.DROP_SLOT, ClientItem(0, 0, null), ClientItem(5, 1, null)),
            InventoryAction(InventoryAction.CONTAINER, windowId = 0, slot = 4, ClientItem(5, 1, stackId = 3), ClientItem(0, 0, null)),
        )
        assertEquals(InventoryTransaction(InventoryTransaction.NORMAL, expected, use = null), decodeClientPacket(packet))
    }

    @Test
    fun `a creative break is a player action`() {
        val packet = packet(36) { writeVarULong(7).writeVarInt(13).writeBlockPos(clicked).writeBlockPos(BlockPos(0, 0, 0)).writeVarInt(1) }

        assertEquals(PlayerAction(7, PlayerAction.CREATIVE_DESTROY_BLOCK, clicked, face = 1), decodeClientPacket(packet))
    }

    @Test
    fun `picking a hotbar slot names the item in it`() {
        val packet = packet(31) {
            writeVarULong(7)
            item(id = 5, stackId = 3)
            writeByte(3).writeByte(3).writeByte(0)
        }

        assertEquals(MobEquipment(7, heldItem = 5, selectedSlot = 3, windowId = 0), decodeClientPacket(packet))
    }

    @Test
    fun `input carries breaking blocks along, each part behind a flag`() {
        val packet = input {
            writeBoolean(true).writeVarInt(0).writeBoolean(false).writeVarUInt(0) // An item use, with no legacy request or slot changes
            itemUse(action = 2, face = 1)
            writeBoolean(false) // No item stack request
            writeBoolean(true).writeVarUInt(2) // Two block actions: starting to break, and cracking
            writeVarInt(0).writeBlockPos(clicked).writeVarInt(1)
            writeVarInt(18).writeBlockPos(clicked).writeVarInt(1)
        }

        val input = decodeClientPacket(packet) as PlayerAuthInput
        assertEquals(ItemUse(ItemUseAction.DESTROY, clicked, face = 1, hotbarSlot = 4, heldItem = 5, click, clickedBlock = GRASS, predictedToWork = true), input.itemUse)
        assertEquals(listOf(BlockAction(0, clicked, 1), BlockAction(18, clicked, 1)), input.blockActions)
        assertEquals(Vec3(0.5f, -58.38f, 0.5f), input.position)
    }

    @Test
    fun `input can carry an item stack request`() {
        val packet = input {
            writeBoolean(false)
            writeBoolean(true).writeVarInt(-5).writeVarUInt(1).writeVarUInt(12).writeByte(14).writeVarUInt(1).writeByte(1) // A creative pick
            writeVarUInt(0).writeIntLE(0)
            writeBoolean(false)
        }

        val input = decodeClientPacket(packet) as PlayerAuthInput
        assertEquals(StackRequest(-5, listOf(StackAction.CraftCreative(1, 1))), input.stackRequest)
        assertEquals(emptyList(), input.blockActions)
    }

    @Test
    fun `a block's data goes as network NBT after where it is`() {
        val bed = NbtMap.builder().putString("id", "Bed").putByte("color", 14).build()
        // A compound with no name, then the id string and the colour byte, each named.
        val expected = "38" + "047705" + "0a00" + "080269640342656401" + "05636f6c6f720e" + "00"
        assertContentEquals(ByteBufUtil.decodeHexDump(expected), BlockActorData(clicked, bed).encode())
    }

    @Test
    fun `block updates name the block by network ID, as an unsigned varint`() {
        val update = UpdateBlock(clicked, 0x80000001.toInt())

        // The position zigzagged, the ID, then the network flag and layer 0.
        assertContentEquals(bytes(0x15, 0x04, 0x77, 0x05, 0x81, 0x80, 0x80, 0x80, 0x08, 0x02, 0x00), update.encode())
    }

    @Test
    fun `level events and sounds happen at a point, with data`() {
        val point = Vec3(2.5f, -59.5f, -2.5f)

        val event = LevelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, point, 0x1234)
        assertContentEquals(bytes(0x19, 0xA2, 0x1F) + POINT + bytes(0xE8, 0x48), event.encode())

        val sound = LevelSoundEvent(LevelSoundEvent.PLACE, point, 0x1234)
        val expected = bytes(0x7B, 0x05) + "place".encodeToByteArray() + POINT + bytes(0xE8, 0x48) +
            bytes(0x01, ':'.code) + bytes(0x00, 0x00) + ByteArray(8) { -1 } + bytes(0x00) // No entity, not a baby, not global, no fire point
        assertContentEquals(expected, sound.encode())
    }

    private fun packet(id: Int, body: ByteBuf.() -> Unit): ByteArray = ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(id).apply(body))

    /** A PlayerAuthInput for a player standing still, with [actions] where the actions riding along go. */
    private fun input(actions: ByteBuf.() -> Unit) = packet(144) {
        writeZero(8).writeFloatLE(0.5f).writeFloatLE(-58.38f).writeFloatLE(0.5f).writeZero(12)
        writeVarUInt(0).writeVarUInt(1).writeVarUInt(0).writeVarInt(1).writeZero(8) // No flags, mouse, normal play, crosshair
        writeVarULong(100).writeZero(12)
        actions()
        writeBoolean(false).writeBoolean(false) // No vehicle
        writeZero(28)
    }

    /** The item use itself, with the player holding item 5 in hotbar slot 4 and clicking grass. */
    private fun ByteBuf.itemUse(action: Int, face: Int) {
        writeVarInt(action).writeByte(1).writeBlockPos(clicked).writeByte(face).writeVarInt(4).writeByte(0)
        item(id = 5, stackId = 3)
        writeVec3(Vec3(2.5f, -58.38f, -1f)).writeVec3(click).writeVarUInt(GRASS)
        writeByte(1).writeByte(0) // Predicted to work, not cooling down
    }

    /** An item as the client sends it: fixed ID, count, aux value, maybe a stack ID, block, and extras with no NBT. */
    private fun ByteBuf.item(id: Int, stackId: Int?) {
        writeShortLE(id).writeShortLE(if (id == 0) 0 else 1).writeVarUInt(0)
        writeBoolean(stackId != null)
        stackId?.let { writeVarInt(it) }
        writeVarUInt(0)
        writeByteArray(ByteArray(10)) // No NBT, nothing it may be placed on or break
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private companion object {
        const val GRASS = 0x5EED

        /** 2.5, -59.5, -2.5 as little-endian floats. */
        val POINT = byteArrayOf(0x00, 0x00, 0x20, 0x40, 0x00, 0x00, 0x6E, 0xC2.toByte(), 0x00, 0x00, 0x20, 0xC0.toByte())
    }
}
