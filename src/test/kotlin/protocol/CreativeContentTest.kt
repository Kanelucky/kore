package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeCategory
import org.koremc.registry.CreativeGroup
import org.koremc.registry.CreativeInventory
import org.koremc.registry.CreativeItem
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertContentEquals

class CreativeContentTest {
    private val stick = ItemDefinition("minecraft:stick", 323, componentBased = false, ItemVersion.NONE)

    /** No extras: no NBT, then no blocks to be placed on or break. */
    private val noExtras = bytes(0x0A, 0x00, 0x00) + ByteArray(8)

    @Test
    fun `an empty slot is an item of ID 0 with an empty buffer`() {
        assertContentEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00), item(null))
    }

    @Test
    fun `items carry their ID, count and aux value, then their extras`() {
        // 323 zigzags to 646, a two-byte varint. One of it, aux value 2, no block.
        assertContentEquals(bytes(0x86, 0x05, 0x01, 0x00, 0x02, 0x00) + noExtras, item(ItemStack(stick, aux = 2)))
    }

    @Test
    fun `block items name the state they place`() {
        val planks = BlockState("minecraft:oak_planks", NbtMap.EMPTY, 18168865)
        val stack = ItemStack(ItemDefinition("minecraft:oak_planks", 5, componentBased = false, ItemVersion.NONE), block = planks)

        val expected = ByteBufUtil.getBytes(Unpooled.buffer().writeVarInt(5).writeShortLE(1).writeVarUInt(0).writeVarInt(planks.networkId)) + noExtras
        assertContentEquals(expected, item(stack))
    }

    @Test
    fun `nbt goes in the extras as little-endian NBT, after a marker and a version`() {
        val stack = ItemStack(stick, nbt = NbtMap.builder().putByte("a", 1).build())

        val nbt = bytes(0x0A, 0x00, 0x00, 0x01, 0x01, 0x00, 'a'.code, 0x01, 0x00)
        val expected = bytes(0x86, 0x05, 0x01, 0x00, 0x00, 0x00) + bytes(20, 0xFF, 0xFF, 0x01) + nbt + ByteArray(8)
        assertContentEquals(expected, item(stack))
    }

    @Test
    fun `a shield's extras end with when it was last raised`() {
        val shield = ItemDefinition("minecraft:shield", 358, componentBased = false, ItemVersion.NONE)

        // 358 zigzags to 716.
        assertContentEquals(bytes(0xCC, 0x05, 0x01, 0x00, 0x00, 0x00) + bytes(18, 0x00, 0x00) + ByteArray(16), item(ItemStack(shield)))
    }

    @Test
    fun `the inventory lists groups, then items numbered from 1 with their group`() {
        val inventory = CreativeInventory(
            groups = listOf(CreativeGroup(CreativeCategory.CONSTRUCTION, "g", icon = null)),
            items = listOf(CreativeItem(ItemStack(stick), group = 0)),
        )

        val expected = bytes(0x91, 0x01) +
            bytes(0x01, 0x01, 0x01, 'g'.code) + item(null) + // One group: construction, named "g", no icon
            bytes(0x01, 0x01) + item(ItemStack(stick)) + bytes(0x00) // One item, number 1, in group 0
        assertContentEquals(expected, CreativeContent(inventory).encode())
    }

    private fun item(stack: ItemStack?) = ByteBufUtil.getBytes(Unpooled.buffer().writeItem(stack))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
