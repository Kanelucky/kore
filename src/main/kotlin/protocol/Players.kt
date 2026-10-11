package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.registry.ItemStack
import org.koremc.world.Vec3
import java.util.UUID

private const val ADD_PLAYER = 12
private const val MOVE_PLAYER = 19
internal const val ANIMATE = 44
private const val PLAYER_LIST = 63

/** A change to the player list: a player joining it, with how they look, or leaving it. */
sealed interface PlayerListEntry {
    val uuid: UUID

    data class Add(
        override val uuid: UUID,
        val entityId: Long,
        val name: String,
        /** Empty for a player who isn't signed in to Xbox Live. */
        val xuid: String,
        /** The device the player plays on, numbered as the client's `DeviceOS` is. */
        val platform: Int,
        val skin: Skin,
    ) : PlayerListEntry

    data class Remove(override val uuid: UUID) : PlayerListEntry
}

/**
 * Changes to the list of players in the game, which the pause screen
 * shows, and where the client looks up the skin of each player it's shown.
 * A player must be on it before they appear.
 */
data class PlayerList(val entries: List<PlayerListEntry>) : ServerPacket {
    override val id get() = PLAYER_LIST

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(entries.size)
        for (entry in entries) {
            // Each entry says which it is twice: as a variant, where Remove is 0 and Add 1, then as an action, where Add is 0 and Remove 1.
            when (entry) {
                is PlayerListEntry.Remove -> out.writeVarUInt(0).writeByte(1).writeUuid(entry.uuid)
                is PlayerListEntry.Add -> {
                    out.writeVarUInt(1).writeByte(0).writeUuid(entry.uuid)
                    out.writeVarLong(entry.entityId)
                    out.writeString(entry.name)
                    out.writeString(entry.xuid)
                    out.writeString("") // Platform chat ID
                    out.writeIntLE(entry.platform)
                    out.writeSkin(entry.skin)
                    out.writeBoolean(false) // Teacher, in Education Edition
                    out.writeBoolean(false) // Host
                    out.writeBoolean(false) // A split screen player sharing another's connection
                    out.writeIntLE(0) // The player's colour, for the locator bar: the client's choice
                }
            }
        }
    }
}

/**
 * Another player appearing to the client: the entity [entityId], who must
 * already be on the player list, standing at [position], and looking the
 * way [pitch], [yaw] and [headYaw] say, holding [heldItem]. The position
 * is where their feet are, though [MovePlayer] gives where their eyes are.
 */
data class AddPlayer(
    val uuid: UUID,
    val name: String,
    val entityId: Long,
    val position: Vec3,
    val pitch: Float,
    val yaw: Float,
    val headYaw: Float,
    val heldItem: ItemStack?,
    val gameMode: GameMode,
    val actorData: List<DataItem>,
    val abilities: Set<Ability>,
    val platform: Int,
) : ServerPacket {
    override val id get() = ADD_PLAYER

    override fun encodeBody(out: ByteBuf) {
        out.writeUuid(uuid)
        out.writeString(name)
        out.writeVarULong(entityId)
        out.writeString("") // Platform chat ID
        out.writeVec3(position)
        out.writeVec3(Vec3(0f, 0f, 0f)) // Velocity
        out.writeFloatLE(pitch)
        out.writeFloatLE(yaw)
        out.writeFloatLE(headYaw)
        out.writeItemInstance(heldItem)
        out.writeVarInt(gameMode.id)
        out.writeActorData(actorData)
        out.writeVarUInt(0) // Int properties, which only entities from behaviour packs have
        out.writeVarUInt(0) // Float properties, likewise
        out.writeAbilities(entityId, PermissionLevel.MEMBER, abilities)
        out.writeVarUInt(0) // Entities it rides or is ridden by
        out.writeString("") // Device ID
        out.writeIntLE(platform)
    }
}

/**
 * Another player moving to [position], their eyes, and looking the way
 * [pitch], [yaw] and [headYaw] say. The client moves them there smoothly.
 */
data class MovePlayer(
    val entityId: Long,
    val position: Vec3,
    val pitch: Float,
    val yaw: Float,
    val headYaw: Float,
    val onGround: Boolean,
) : ServerPacket {
    override val id get() = MOVE_PLAYER

    override fun encodeBody(out: ByteBuf) {
        out.writeVarULong(entityId)
        out.writeVec3(position)
        out.writeFloatLE(pitch)
        out.writeFloatLE(yaw)
        out.writeFloatLE(headYaw)
        out.writeByte(NORMAL)
        out.writeBoolean(onGround)
        out.writeVarULong(0) // The entity they ride: none
        out.writeBoolean(false) // How they were teleported, which only a teleport has
        out.writeVarULong(0) // The input tick, which only the player's own client goes by
    }

    private companion object {
        /** A move like any other, rather than a teleport or a correction. */
        const val NORMAL = 0
    }
}

/**
 * An entity doing something brief that the client animates, like a player
 * swinging their arm. The client sends it when its own player swings, with
 * [swingSource] saying why, like `mine` or `build`, and the server passes
 * it on for everyone else to see.
 */
data class Animate(val action: Int, val entityId: Long, val data: Float = 0f, val swingSource: String? = null) : ClientPacket, ServerPacket {
    override val id get() = ANIMATE

    override fun encodeBody(out: ByteBuf) {
        out.writeByte(action)
        out.writeVarULong(entityId)
        out.writeFloatLE(data)
        out.writeBoolean(swingSource != null)
        swingSource?.let(out::writeString)
    }

    companion object {
        const val SWING_ARM = 1
    }
}

/**
 * What another player holds, the [stack] in their hotbar [slot], as the
 * server shows it with `MobEquipment`. The client sends the same packet
 * to say which slot its own player picked.
 */
data class ShowEquipment(val entityId: Long, val stack: ItemStack?, val slot: Int) : ServerPacket {
    override val id get() = MOB_EQUIPMENT

    override fun encodeBody(out: ByteBuf) {
        out.writeVarULong(entityId)
        out.writeItemInstance(stack)
        out.writeByte(slot) // The slot
        out.writeByte(slot) // The hotbar slot it's picked in, which for a player is the same
        out.writeByte(0) // The window: the player's own items
    }
}

internal fun decodeAnimate(buffer: ByteBuf): Animate {
    val action = buffer.readUnsignedByte().toInt()
    val entityId = buffer.readVarULong()
    val data = buffer.readFloatLE()
    val swingSource = if (buffer.readBoolean()) buffer.readString() else null
    return Animate(action, entityId, data, swingSource)
}
