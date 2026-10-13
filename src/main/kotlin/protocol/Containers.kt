package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.registry.ItemStack
import org.koremc.world.BlockPos

private const val BLOCK_EVENT = 26
internal const val PLAYER_TOGGLE_CRAFTER_SLOT_REQUEST = 306
private const val INVENTORY_CONTENT = 49
private const val CONTAINER_SET_DATA = 51
private const val PLAYER_ENCHANT_OPTIONS = 146

/** The window of the slots the inventory screen itself uses: the cursor, the crafting grid and the created output. */
const val UI_WINDOW = 124

/** The kinds of window the client opens, by their wire values. */
object ContainerType {
    /** A chest, barrel, shulker box or ender chest: rows of slots. */
    const val CONTAINER = 0

    /** A crafting table's three by three grid. */
    const val WORKBENCH = 1
    const val FURNACE = 2
    const val ENCHANTMENT = 3
    const val BREWING_STAND = 4
    const val ANVIL = 5
    const val DISPENSER = 6
    const val DROPPER = 7
    const val HOPPER = 8
    const val LOOM = 24

    /** A lectern's book, open to read, which has no window of its own. */
    const val LECTERN = 25
    const val GRINDSTONE = 26
    const val BLAST_FURNACE = 27
    const val SMOKER = 28
    const val STONECUTTER = 29
    const val SMITHING_TABLE = 33
    const val CRAFTER = 36
}

/** A player switching the empty [slot] of the crafter at [position] off, as [disabled], or back on. */
data class ToggleCrafterSlot(val position: BlockPos, val slot: Int, val disabled: Boolean) : ClientPacket

internal fun decodeToggleCrafterSlot(buffer: ByteBuf) =
    ToggleCrafterSlot(BlockPos(buffer.readIntLE(), buffer.readIntLE(), buffer.readIntLE()), buffer.readUnsignedByte().toInt(), buffer.readBoolean())

/**
 * What every slot of a window holds, with each stack's ID, or nothing.
 * The client takes it as it is, as when a window opens.
 */
data class InventoryContent(val window: Int, val slots: List<Pair<ItemStack, Int>?>) : ServerPacket {
    override val id get() = INVENTORY_CONTENT

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(window)
        out.writeVarUInt(slots.size)
        for (slot in slots) out.writeItemInstance(slot?.first, slot?.second ?: 0)
        out.writeByte(0).writeBoolean(false) // A container within the window, and no dynamic ID, which only bundles have
        out.writeItemInstance(null) // A storage item, like a bundle, it's inside
    }
}

/** Something a block does, like a chest's lid opening, at [position]. */
data class BlockEvent(val position: BlockPos, val type: Int, val data: Int) : ServerPacket {
    override val id get() = BLOCK_EVENT

    override fun encodeBody(out: ByteBuf) {
        out.writeBlockPos(position)
        out.writeVarInt(type)
        out.writeVarInt(data)
    }

    companion object {
        /** A chest, shulker box or ender chest opening (data 1) or closing (data 0). */
        const val CHANGE_STATE = 1
    }
}

/**
 * One of a window's numbers changing, like how far along a furnace is,
 * which the client shows in its screen: [property] of the window [window]
 * is now [value].
 */
data class ContainerSetData(val window: Int, val property: Int, val value: Int) : ServerPacket {
    override val id get() = CONTAINER_SET_DATA

    override fun encodeBody(out: ByteBuf) {
        out.writeByte(window)
        out.writeVarInt(property)
        out.writeVarInt(value)
    }

    companion object {
        /** How many ticks a furnace has cooked what it's cooking for. */
        const val FURNACE_COOK_TIME = 0

        /** How many ticks the fuel burning in a furnace has left, and how many it had, which the flame shows. */
        const val FURNACE_LIT_TIME = 1
        const val FURNACE_LIT_DURATION = 2

        /** How many ticks a brewing stand has left to brew, from 400 down. */
        const val BREWING_TIME = 0

        /** How many brews a brewing stand's blaze powder has left, and how many it had. */
        const val BREWING_FUEL = 1
        const val BREWING_FUEL_TOTAL = 2
    }
}

/** An enchantment at a level, as an enchanting table offers it. */
data class OfferedEnchantment(val id: Int, val level: Int)

/**
 * One of the three things an enchanting table offers: the level a player
 * needs, the enchantments, the made-up words it shows, and the number the
 * client names it by when the player picks it.
 */
data class EnchantOption(val cost: Int, val slot: Int, val enchantments: List<OfferedEnchantment>, val name: String, val networkId: Int)

/** What the enchanting table a player has open offers for the item in it. */
data class PlayerEnchantOptions(val options: List<EnchantOption>) : ServerPacket {
    override val id get() = PLAYER_ENCHANT_OPTIONS

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(options.size)
        for (option in options) {
            out.writeVarUInt(option.cost)
            out.writeIntLE(option.slot)
            // Three lists, of which the client shows the middle one.
            out.writeVarUInt(0)
            out.writeVarUInt(option.enchantments.size)
            for (enchantment in option.enchantments) out.writeByte(enchantment.id).writeByte(enchantment.level)
            out.writeVarUInt(0)
            out.writeString(option.name)
            out.writeVarUInt(option.networkId)
        }
    }
}
