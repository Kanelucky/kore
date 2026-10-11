package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack

/**
 * The block entity a block keeps when [placer] places it at [pos] from
 * [item], for blocks whose looks don't all fit in their state: a head's
 * turn on the floor, a bed's colour, and a banner's colour and patterns.
 * The client makes a plain one for any other block that needs one. Null
 * for a block that keeps nothing of its own.
 */
internal fun blockEntityFor(state: BlockState, pos: BlockPos, placer: Placer, item: ItemStack?): NbtMap? {
    val name = state.name
    return when {
        name.endsWith("_head") || name.endsWith("_skull") -> {
            // A head on the floor turns in sixteen steps to face the way the player looks. One on a wall faces out of it.
            val standing = state.properties[FACING_DIRECTION] == Face.UP.ordinal
            val steps = if (standing) Math.floorMod(Math.round(placer.yaw * 16f / 360f), 16) else 0
            NbtMap.builder().putString("id", "Skull").putByte("SkullType", (-1).toByte()).putFloat("Rotation", steps * 22.5f).build()
        }
        HEAD_PIECE in state.properties && name == "minecraft:bed" -> NbtMap.builder().putString("id", "Bed").putByte("color", (item?.aux ?: 0).toByte()).build()
        name == "minecraft:standing_banner" || name == "minecraft:wall_banner" -> {
            val banner = NbtMap.builder().putString("id", "Banner").putInt("Base", item?.aux ?: 0).putInt("Type", item?.nbt?.getInt("Type", 0) ?: 0)
            item?.nbt?.getList("Patterns", NbtType.COMPOUND)?.let { banner.putList("Patterns", NbtType.COMPOUND, it) }
            banner.build()
        }
        else -> null
    }
}

/**
 * Pairs the chest just placed at [pos] with one beside it, if there's a
 * chest of the same kind to its left or right, facing the same way, that
 * isn't paired already. Both remember where the other is. Returns where
 * the other is, as the data it keeps changed, or nothing.
 */
internal fun FlatWorld.pairChest(pos: BlockPos): List<BlockPos> {
    val chest = stateAt(pos) ?: return emptyList()
    val facing = Face.entries.find { it.id == chest.properties[CARDINAL] } ?: return emptyList()
    val partner = listOf(facing.counterClockwise, facing.clockwise).map(pos::side).firstOrNull { side ->
        val other = stateAt(side)
        other?.name == chest.name && other.properties[CARDINAL] == chest.properties[CARDINAL] && blockEntityAt(side)?.containsKey(PAIR_X) != true
    } ?: return emptyList()
    setBlockEntity(pos, pairedChest(partner))
    setBlockEntity(partner, pairedChest(pos))
    return listOf(partner)
}

/** A chest's block entity, paired with the chest at [other]. */
private fun pairedChest(other: BlockPos) = NbtMap.builder().putString("id", "Chest").putInt(PAIR_X, other.x).putInt(PAIR_Z, other.z).build()

/**
 * Lets the chest paired with the one whose block entity was [chest] stand
 * alone, now that the other's gone. Returns where it is, or null if
 * [chest] wasn't a paired chest's.
 */
internal fun FlatWorld.unpairChestFrom(chest: NbtMap): BlockPos? {
    if (!chest.containsKey(PAIR_X)) return null
    val partner = BlockPos(chest.getInt(PAIR_X), chest.getInt("y"), chest.getInt(PAIR_Z))
    if (stateAt(partner)?.isChest != true) return null
    setBlockEntity(partner, NbtMap.builder().putString("id", "Chest").build())
    return partner
}

private const val PAIR_X = "pairx"
private const val PAIR_Z = "pairz"
