package org.koremc.world

import org.koremc.protocol.ContainerName
import org.koremc.registry.BlockState

/** How many slots a chest, barrel, shulker box or ender chest has. */
const val STORAGE_SLOTS = 27

/**
 * The kinds of block that keep items, by the name the client gives their
 * slots and the sounds they open and close with. A chest's, a shulker
 * box's or an ender chest's lid opens; a barrel opens by its state.
 */
enum class Storage(val containerName: Int, val openSound: String, val closeSound: String, val lid: Boolean) {
    CHEST(ContainerName.LEVEL_ENTITY, "chest.open", "chest.closed", lid = true),

    /** Each player's own items, whichever ender chest they open. */
    ENDER_CHEST(ContainerName.LEVEL_ENTITY, "enderchest.open", "enderchest.closed", lid = true),
    SHULKER_BOX(ContainerName.SHULKER_BOX, "shulkerbox.open", "shulkerbox.closed", lid = true),
    BARREL(ContainerName.BARREL, "block.barrel.open", "block.barrel.close", lid = false),
}

/** The kind of block that keeps items this is, or null if it isn't one. Chests count trapped and copper chests in. */
val BlockState.storage: Storage?
    get() = when {
        name == ENDER_CHEST -> Storage.ENDER_CHEST
        isChest -> Storage.CHEST
        name.endsWith("shulker_box") -> Storage.SHULKER_BOX
        name == BARREL -> Storage.BARREL
        else -> null
    }

private const val ENDER_CHEST = "minecraft:ender_chest"
internal const val BARREL = "minecraft:barrel"

/** A barrel's state for being open. */
internal const val BARREL_OPEN = "open_bit"

/**
 * Whether something keeps the block at [pos] from opening: a chest or
 * ender chest with a solid block on top, or either half of a double chest
 * with one, and a shulker box with a solid block where its lid would go.
 * Barrels open whatever's around them.
 */
fun FlatWorld.isBlockedOpen(pos: BlockPos): Boolean {
    val state = stateAt(pos) ?: return true
    fun solid(at: BlockPos) = stateAt(at)?.let { it.isFullCube && !it.letsLightThrough } == true
    return when (state.storage) {
        Storage.CHEST, Storage.ENDER_CHEST -> chestHalves(pos).any { solid(it.side(Face.UP)) }
        Storage.SHULKER_BOX -> Face.entries.getOrNull(state.properties[FACING_DIRECTION] as? Int ?: Face.UP.ordinal)?.let { solid(pos.side(it)) } == true
        else -> false
    }
}

/** Blocks that are whole cubes, but see-through, which don't keep a chest shut: glass, leaves and ice. */
private val BlockState.letsLightThrough: Boolean
    get() = name.endsWith("glass") || name.endsWith("_leaves") || name.endsWith("ice")

/**
 * The chest at [pos], and the one it's paired with, if any: the half on
 * the left first, as a player facing their front sees them, which a double
 * chest's window shows the items of first.
 */
fun FlatWorld.chestHalves(pos: BlockPos): List<BlockPos> {
    val chest = stateAt(pos) ?: return listOf(pos)
    val data = blockEntityAt(pos) ?: return listOf(pos)
    if (!chest.isChest || !data.containsKey(PAIR_X)) return listOf(pos)
    val partner = BlockPos(data.getInt(PAIR_X), pos.y, data.getInt(PAIR_Z))
    if (stateAt(partner)?.isChest != true) return listOf(pos)
    val facing = Face.entries.find { it.id == chest.properties[CARDINAL] } ?: return listOf(pos, partner)
    // Facing the chest's front, a player looks the opposite way, and their left is the chest's turn clockwise.
    return if (partner == pos.side(facing.clockwise)) listOf(partner, pos) else listOf(pos, partner)
}
