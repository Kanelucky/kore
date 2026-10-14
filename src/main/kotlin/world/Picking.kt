package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

/** Where a block's item keeps what the block kept, from being picked with it, which placing it puts back. */
private const val BLOCK_ENTITY_TAG = "BlockEntityTag"

/** The note an item carrying a block's data shows under its name. */
private const val DATA_NOTE = "+(DATA)"

/** What a block keeps that says where it is, or what it's paired with there, which a picked block's item leaves behind. */
private val PLACE_KEYS = setOf("x", "y", "z", "pairx", "pairz", "pairlead", "isMovable")

/** Blocks no one can hold, which pick as nothing: air, liquids, fire and portals. */
private val UNPICKABLE = setOf(
    AIR, WATER, FLOWING_WATER, LAVA, FLOWING_LAVA, "minecraft:fire", "minecraft:soul_fire", "minecraft:portal", "minecraft:end_portal",
    "minecraft:end_gateway", "minecraft:moving_block", "minecraft:bubble_column",
)

/** Blocks that pick as another block's item: as they are unlit, unpowered or uninverted, and a piston's head as its piston. */
private val PICKED_AS = mapOf(
    LIT_REDSTONE_LAMP to REDSTONE_LAMP, UNLIT_REDSTONE_TORCH to REDSTONE_TORCH, INVERTED_DAYLIGHT_DETECTOR to DAYLIGHT_DETECTOR,
    "minecraft:lit_redstone_ore" to "minecraft:redstone_ore", "minecraft:lit_deepslate_redstone_ore" to "minecraft:deepslate_redstone_ore",
    "minecraft:piston_arm_collision" to PISTON, "minecraft:sticky_piston_arm_collision" to STICKY_PISTON,
    CAVE_VINES_BERRIES_HEAD to "minecraft:glow_berries", CAVE_VINES_BERRIES_BODY to "minecraft:glow_berries",
) + FurnaceKind.entries.associate { it.litBlock to it.block }

/**
 * The item a player picks the block [state] as, a middle click on it:
 * its own, or the item that places it, like seeds for a crop or a sign for
 * one on a wall, a slab for a double slab, and a bed or banner in the
 * colour its block entity, [blockEntity], kept. Null for a block no one
 * can hold, like water or fire.
 */
internal fun pickedItem(state: BlockState, blockEntity: NbtMap?, items: ItemStacks): ItemStack? {
    if (state.name in UNPICKABLE) return null
    val name = PICKED_AS[state.name] ?: singleSlabOf(state.name) ?: itemPlacing(state.name) ?: state.name
    return items.of(name, aux = keptColour(blockEntity) ?: 0)
}

/**
 * [item], the block at [pos] picked, carrying what the block keeps
 * besides its state, as picking it with its data does in creative: a
 * chest's or furnace's items, a sign's writing, a banner's patterns, and
 * the like, noted on the item. Where the block was, and a chest it was
 * paired with, it leaves behind. Null for a block that keeps nothing.
 */
internal fun FlatWorld.withBlockData(item: ItemStack, pos: BlockPos): ItemStack? {
    val data = savedBlockEntity(pos) ?: return null
    val kept = data.toBuilder().apply { PLACE_KEYS.forEach { remove(it) } }.build()
    val note = NbtMap.builder().putList("Lore", NbtType.STRING, listOf(DATA_NOTE)).build()
    return item.copy(nbt = item.nbt.toBuilder().putCompound(BLOCK_ENTITY_TAG, kept).putCompound("display", note).build())
}

/**
 * Puts what [item] carries from a block picked with its data on the block
 * placed from it at [pos], as the block kept it, with the items it held,
 * made with [items]. A chest the new block paired with on placing stays
 * paired. Returns whether the item carried any.
 */
internal fun FlatWorld.restoreBlockData(pos: BlockPos, item: ItemStack, items: ItemStacks): Boolean {
    val data = item.nbt.getCompound(BLOCK_ENTITY_TAG, null) ?: return false
    val placed = blockEntityAt(pos)?.toBuilder() ?: NbtMap.builder()
    for ((key, value) in data) if (key !in PLACE_KEYS) placed[key] = value
    restoreBlockEntity(placed.putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z).build(), items)
    return true
}
