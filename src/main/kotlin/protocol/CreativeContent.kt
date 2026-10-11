package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemStack

private const val CREATIVE_CONTENT = 145

/** Shields carry one more field than other items: the tick their holder last raised them. */
private const val SHIELD = "minecraft:shield"

/**
 * The creative inventory: each tab's groups, then every item, each naming
 * its group by index. Items are numbered from 1 in the order they're
 * listed. Like the item registry, it's the same for every player and
 * encoded once.
 */
class CreativeContent(val inventory: CreativeInventory) : ServerPacket {
    override val id get() = CREATIVE_CONTENT

    private val body = encodeOnce {
        writeVarUInt(inventory.groups.size)
        for (group in inventory.groups) {
            writeByte(group.category.id)
            writeString(group.name)
            writeItem(group.icon)
        }
        writeVarUInt(inventory.items.size)
        inventory.items.forEachIndexed { i, item ->
            writeVarUInt(i + 1)
            writeItem(item.stack)
            writeVarUInt(item.group)
        }
    }

    override fun encodeBody(out: ByteBuf) {
        out.writeBytes(body)
    }
}

/**
 * [stack] as a network item, the way the server describes items that
 * aren't in an inventory, like the creative inventory's: its runtime ID,
 * count, aux value and block, then a buffer of extras. No stack at all is
 * an item of ID 0 with an empty buffer.
 */
fun ByteBuf.writeItem(stack: ItemStack?): ByteBuf {
    if (stack == null) return writeVarInt(0).writeShortLE(0).writeVarUInt(0).writeVarInt(0).writeVarUInt(0)

    writeVarInt(stack.item.runtimeId)
    writeShortLE(stack.count)
    writeVarUInt(stack.aux)
    writeVarInt(stack.block?.networkId ?: 0)
    return writeItemExtras(stack)
}

/**
 * [stack] as a network item that may carry the ID of the stack it is, the
 * way slots and item entities describe theirs: its runtime ID in a fixed
 * 16 bits, count, aux value, stack ID if it has one (0 for none), block
 * and extras. No stack at all is an item of ID 0 with an empty buffer.
 */
fun ByteBuf.writeItemInstance(stack: ItemStack?, stackId: Int = 0): ByteBuf {
    if (stack == null) return writeShortLE(0).writeShortLE(0).writeVarUInt(0).writeBoolean(false).writeVarUInt(0).writeVarUInt(0)

    writeShortLE(stack.item.runtimeId)
    writeShortLE(stack.count)
    writeVarUInt(stack.aux)
    writeBoolean(stackId != 0)
    if (stackId != 0) writeVarInt(stackId)
    writeVarUInt(stack.block?.networkId ?: 0)
    return writeItemExtras(stack)
}

/**
 * A buffer of [stack]'s extras: its NBT, the blocks it may be placed on
 * and break (none, for now), and for a shield, when it was last raised.
 */
private fun ByteBuf.writeItemExtras(stack: ItemStack): ByteBuf {
    val extras = Unpooled.buffer()
    if (stack.nbt.isEmpty()) {
        extras.writeShortLE(0)
    } else {
        extras.writeShortLE(-1).writeByte(1) // NBT follows, in version 1 of the format
        extras.writeNbtLE(stack.nbt)
    }
    extras.writeIntLE(0) // Blocks it may be placed on
    extras.writeIntLE(0) // Blocks it may break
    if (stack.item.name == SHIELD) extras.writeLongLE(0) // The tick it was last raised
    writeVarUInt(extras.readableBytes())
    return writeBytes(extras)
}

/** [tag] as little-endian NBT, with fixed-size numbers, unlike network NBT. */
private fun ByteBuf.writeNbtLE(tag: NbtMap): ByteBuf = apply { NbtUtils.createWriterLE(ByteBufOutputStream(this)).writeTag(tag) }
