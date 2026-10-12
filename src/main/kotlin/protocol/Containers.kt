package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.registry.ItemStack
import org.koremc.world.BlockPos

private const val BLOCK_EVENT = 26
private const val INVENTORY_CONTENT = 49

/** The window of the slots the inventory screen itself uses: the cursor, the crafting grid and the created output. */
const val UI_WINDOW = 124

/** The kinds of window the client opens, by their wire values. */
object ContainerType {
    /** A chest, barrel, shulker box or ender chest: rows of slots. */
    const val CONTAINER = 0

    /** A crafting table's three by three grid. */
    const val WORKBENCH = 1
}

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
