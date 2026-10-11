package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.world.BlockPos
import org.koremc.world.Vec3

private const val UPDATE_BLOCK = 21
private const val LEVEL_EVENT = 25
internal const val INVENTORY_TRANSACTION = 30
internal const val MOB_EQUIPMENT = 31
internal const val PLAYER_ACTION = 36
private const val LEVEL_SOUND_EVENT = 123

/** The inventory transaction type that uses an item on a block. */
private const val USE_ITEM_TRANSACTION = 2

/** The client's prediction for an item use that works. One that fails is 0. */
private const val PREDICTED_SUCCESS = 1

/** What the player did with their item. Declared in wire order: each is sent as its ordinal. */
enum class ItemUseAction { PLACE, USE, DESTROY, USE_AS_ATTACK }

/**
 * The player using their held item on a block, reported once the client
 * has predicted what happens: placing a block against [face] of the block
 * at [position], or destroying the block there.
 */
data class ItemUse(
    val action: ItemUseAction,
    val position: BlockPos,
    /** The face clicked, numbered as [org.koremc.world.Face] has them. */
    val face: Int,
    val hotbarSlot: Int,
    /** The runtime ID of the item the client says the player holds, or 0 for none. */
    val heldItem: Int,
    /** Where on [face] the player clicked, from the block's lowest corner, so each coordinate runs from 0 to 1. */
    val clickPosition: Vec3,
    /** The network ID of the block the client thinks is at [position]. */
    val clickedBlock: Int,
    /** Whether the client found the use works, and so changed its world as it expects the server to. */
    val predictedToWork: Boolean,
)

/**
 * A transaction from the inventory system that came before item stack
 * requests. The client still sends one when the player uses an item on a
 * block, which Kore reads as [use]. It leaves the other kinds unread.
 */
data class InventoryTransaction(val type: Int, val use: ItemUse?) : ClientPacket

/**
 * Something the player did that has no packet of its own, like breaking a
 * block in creative. Most such actions ride in `PlayerAuthInput` now.
 */
data class PlayerAction(val runtimeId: Long, val action: Int, val position: BlockPos, val face: Int) : ClientPacket {
    companion object {
        /** A survival player starting to break a block. */
        const val START_DESTROY_BLOCK = 0

        /** A survival player giving up on breaking a block. */
        const val ABORT_DESTROY_BLOCK = 1

        /** A survival player done breaking a block, whether it broke or not. */
        const val STOP_DESTROY_BLOCK = 2

        /** A creative player breaking a block, which the client has already done. */
        const val CREATIVE_DESTROY_BLOCK = 13

        /** A tick of a survival player breaking a block, for which the client expects bits to fly off it. */
        const val CRACK_BLOCK = 18
    }
}

/** A step in breaking a block, as `PlayerAuthInput` carries them: starting, cracking, stopping and so on, numbered as [PlayerAction]s are. */
data class BlockAction(val action: Int, val position: BlockPos, val face: Int)

/** The player choosing the item they hold, by picking a hotbar slot. */
data class MobEquipment(val runtimeId: Long, val heldItem: Int, val selectedSlot: Int, val windowId: Int) : ClientPacket

/** A block that's now [networkId], which the client takes as it is, whatever it predicted. */
data class UpdateBlock(val position: BlockPos, val networkId: Int) : ServerPacket {
    override val id get() = UPDATE_BLOCK

    override fun encodeBody(out: ByteBuf) {
        out.writeBlockPos(position)
        out.writeVarUInt(networkId)
        out.writeVarUInt(BLOCK_UPDATE_NETWORK)
        out.writeVarUInt(0) // Layer: the block itself, rather than water inside it
    }

    private companion object {
        /** The flag for a change that comes over the network, rather than one the client made itself. */
        const val BLOCK_UPDATE_NETWORK = 0b10
    }
}

/** Something happening at a point in the world that the client shows, like a block's particles as it breaks. */
data class LevelEvent(val event: Int, val position: Vec3, val data: Int) : ServerPacket {
    override val id get() = LEVEL_EVENT

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(event)
        out.writeVec3(position)
        out.writeVarInt(data)
    }

    companion object {
        /** The burst of particles and the sound of a block breaking. The data is the block's network ID. */
        const val PARTICLES_DESTROY_BLOCK = 2001

        /**
         * Cracks spreading over a block as it's broken. The data is how much
         * of the way they get each tick, out of 65535, and the client carries
         * on spreading them until they stop.
         */
        const val START_BLOCK_CRACKING = 3600
        const val STOP_BLOCK_CRACKING = 3601

        /**
         * Bits flying off the face of a block being broken, plus the face's
         * number, from down to east. The data is the block's network ID. An
         * older event packs the face into the ID's top byte, which a hashed
         * network ID has no room for.
         */
        const val PARTICLES_CRACK_BLOCK_DOWN = 3603
    }
}

/** A sound at a point in the world, by the game's name for it, like [PLACE]. */
data class LevelSoundEvent(val sound: String, val position: Vec3, val data: Int) : ServerPacket {
    override val id get() = LEVEL_SOUND_EVENT

    override fun encodeBody(out: ByteBuf) {
        out.writeString(sound)
        out.writeVec3(position)
        out.writeVarInt(data)
        out.writeString(":") // The entity type making it: none
        out.writeBoolean(false) // Whether that's a baby, which pitches the sound up
        out.writeBoolean(false) // Whether everyone hears it, wherever they are
        out.writeLongLE(-1) // That entity's unique ID
        out.writeBoolean(false) // A separate point to fire it at
    }

    companion object {
        /** A block being placed. The data is the block's network ID, so it makes the block's own sound. */
        const val PLACE = "place"

        /** A button popping back out. The data is the button's network ID, for the click of what it's made of. */
        const val BUTTON_CLICK_OFF = "button.click_off"
    }
}

internal fun decodeInventoryTransaction(buffer: ByteBuf): InventoryTransaction {
    buffer.skipLegacyRequest()
    val type = buffer.readVarUInt()
    buffer.skipActions()
    return InventoryTransaction(type, if (type == USE_ITEM_TRANSACTION) buffer.readItemUse() else null)
}

internal fun decodePlayerAction(buffer: ByteBuf): PlayerAction {
    val runtimeId = buffer.readVarULong()
    val action = buffer.readVarInt()
    val position = buffer.readBlockPos()
    buffer.readBlockPos() // Where it resulted, which none of the actions Kore reads use
    return PlayerAction(runtimeId, action, position, face = buffer.readVarInt())
}

internal fun decodeMobEquipment(buffer: ByteBuf): MobEquipment {
    val runtimeId = buffer.readVarULong()
    val heldItem = buffer.readItemId()
    buffer.readUnsignedByte() // The slot, which for a player is the same hotbar slot as the next
    return MobEquipment(runtimeId, heldItem, selectedSlot = buffer.readUnsignedByte().toInt(), windowId = buffer.readUnsignedByte().toInt())
}

/** An item use as `PlayerAuthInput` carries it: a transaction without the type, which can only be this one. */
internal fun ByteBuf.readPackedItemUse(): ItemUse {
    skipLegacyRequest()
    skipActions()
    return readItemUse()
}

internal fun ByteBuf.readBlockAction() = BlockAction(readVarInt(), readBlockPos(), readVarInt())

/** Skips a legacy request ID, and the slots a valid one asks to have resent. Item stack requests do that job now. */
private fun ByteBuf.skipLegacyRequest() {
    readVarInt()
    if (readBoolean()) readList { readUnsignedByte(); readByteArray() } // A container, then its slots
}

/** Skips the slot changes the client predicts a transaction makes, which Kore works out for itself. */
private fun ByteBuf.skipActions() {
    readList {
        readVarUInt() // Where the items come from: a container, the world, the creative inventory
        if (readBoolean()) readByte() // The container's window
        if (readBoolean()) readVarUInt() // Flags, for items from the world
        readVarUInt() // Slot
        readItemId() // What was there
        readItemId() // What's there now
    }
}

private fun ByteBuf.readItemUse(): ItemUse {
    val action = readVarInt().let { ItemUseAction.entries.getOrNull(it) ?: throw PacketException("unknown item use action $it") }
    readUnsignedByte() // What triggered it: the player's input, or that input held down since an earlier tick
    val position = readBlockPos()
    val face = readUnsignedByte().toInt()
    val hotbarSlot = readVarInt()
    readUnsignedByte() // The hand holding the item
    val heldItem = readItemId()
    readVec3() // Where the player was
    val clickPosition = readVec3()
    val clickedBlock = readVarUInt()
    val predictedToWork = readUnsignedByte().toInt() == PREDICTED_SUCCESS
    readUnsignedByte() // Whether the item was cooling down
    return ItemUse(action, position, face, hotbarSlot, heldItem, clickPosition, clickedBlock, predictedToWork)
}

/**
 * Reads an item as the client sends it outside item stack requests, and
 * returns its runtime ID, which is 0 for none. These items have a fixed
 * 16-bit ID and may carry a stack ID, unlike those the server sends. The
 * count, aux value, stack ID, block and extras are read past.
 */
private fun ByteBuf.readItemId(): Int {
    val id = readShortLE().toInt()
    readUnsignedShortLE() // Count
    readVarUInt() // Aux value
    if (readBoolean()) readVarInt() // Stack ID
    readVarUInt() // Block network ID
    readByteArray() // Extras
    return id
}
