package org.koremc.protocol

import io.netty.buffer.ByteBuf

private const val UPDATE_ABILITIES = 187

/** The base layer, under any that commands or spectating add on top. */
private const val BASE_LAYER = 1

/** What a player may do. Declared in bit order: each ability is the bit at its ordinal. */
enum class Ability {
    BUILD,
    MINE,
    DOORS_AND_SWITCHES,
    OPEN_CONTAINERS,
    ATTACK_PLAYERS,
    ATTACK_MOBS,
    OPERATOR_COMMANDS,
    TELEPORT,
    INVULNERABLE,
    FLYING,
    MAY_FLY,
    INSTANT_BUILD,
    LIGHTNING,
    FLY_SPEED,
    WALK_SPEED,
    MUTED,
    WORLD_BUILDER,
    NO_CLIP,
    PRIVILEGED_BUILDER,
    VERTICAL_FLY_SPEED,
}

/** What a player in [mode] may do. */
fun abilitiesFor(mode: GameMode): Set<Ability> {
    val interact = setOf(Ability.DOORS_AND_SWITCHES, Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS)
    return when (mode) {
        GameMode.SURVIVAL, GameMode.DEFAULT -> interact + Ability.BUILD + Ability.MINE
        GameMode.ADVENTURE -> interact
        GameMode.CREATIVE -> interact + setOf(Ability.BUILD, Ability.MINE, Ability.MAY_FLY, Ability.INSTANT_BUILD, Ability.INVULNERABLE)
        GameMode.SPECTATOR -> setOf(Ability.MAY_FLY, Ability.FLYING, Ability.NO_CLIP, Ability.INVULNERABLE)
    }
}

/**
 * A player's abilities and speeds, as a single base layer that settles
 * every ability one way or the other.
 */
data class UpdateAbilities(
    /** The player's entity ID. */
    val entityId: Long,
    val permissions: PermissionLevel,
    val abilities: Set<Ability>,
    val walkSpeed: Float = 0.1f,
    val flySpeed: Float = 0.05f,
    val verticalFlySpeed: Float = 1f,
) : ServerPacket {
    override val id get() = UPDATE_ABILITIES

    override fun encodeBody(out: ByteBuf) {
        out.writeAbilities(entityId, permissions, abilities, walkSpeed, flySpeed, verticalFlySpeed)
    }
}

/** A player's abilities as `UpdateAbilities` and `AddPlayer` carry them. */
internal fun ByteBuf.writeAbilities(
    entityId: Long,
    permissions: PermissionLevel,
    abilities: Set<Ability>,
    walkSpeed: Float = 0.1f,
    flySpeed: Float = 0.05f,
    verticalFlySpeed: Float = 1f,
): ByteBuf {
    writeLongLE(entityId)
    writeByte(permissions.ordinal)
    writeByte(0) // Command permissions: the lowest
    writeVarUInt(1)
    writeShortLE(BASE_LAYER)
    writeIntLE((1 shl Ability.entries.size) - 1) // Which abilities this layer settles: all of them
    writeIntLE(abilities.fold(0) { bits, ability -> bits or (1 shl ability.ordinal) })
    writeFloatLE(flySpeed)
    writeFloatLE(verticalFlySpeed)
    return writeFloatLE(walkSpeed)
}
