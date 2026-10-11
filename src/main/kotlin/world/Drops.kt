package org.koremc.world

import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack

/**
 * Blocks that drop another item than themselves, and how many. Ores drop
 * the least they can, since nothing gives a tool Fortune yet.
 */
private val DROPS_INSTEAD = buildMap {
    val dirt = "minecraft:dirt" to 1
    for (block in listOf("grass_block", "podzol", "mycelium", "farmland", "grass_path")) put("minecraft:$block", dirt)
    put("minecraft:stone", "minecraft:cobblestone" to 1)
    put("minecraft:deepslate", "minecraft:cobbled_deepslate" to 1)
    val ores = mapOf(
        "coal" to ("minecraft:coal" to 1), "iron" to ("minecraft:raw_iron" to 1), "copper" to ("minecraft:raw_copper" to 2),
        "gold" to ("minecraft:raw_gold" to 1), "diamond" to ("minecraft:diamond" to 1), "emerald" to ("minecraft:emerald" to 1),
        "lapis" to ("minecraft:lapis_lazuli" to 4), "redstone" to ("minecraft:redstone" to 4),
    )
    for ((ore, drop) in ores) {
        put("minecraft:${ore}_ore", drop)
        put("minecraft:deepslate_${ore}_ore", drop)
    }
    put("minecraft:lit_redstone_ore", "minecraft:redstone" to 4)
    put("minecraft:lit_deepslate_redstone_ore", "minecraft:redstone" to 4)
    put("minecraft:nether_gold_ore", "minecraft:gold_nugget" to 2)
    put("minecraft:quartz_ore", "minecraft:quartz" to 1)
}

/** Blocks that drop nothing, not even themselves, without Silk Touch, which nothing gives yet. */
private val DROPS_NOTHING = setOf(
    "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:short_grass", "minecraft:tall_grass",
    "minecraft:fern", "minecraft:large_fern", "minecraft:seagrass", "minecraft:web", "minecraft:fire", "minecraft:soul_fire",
)

/**
 * What blocks drop when players break them. Most drop their own item,
 * which shares the block's name and, in the creative inventory, carries the
 * block state it places. Some drop another, like grass dropping dirt, and
 * some drop nothing: glass, leaves, ice and plain grasses. A double slab
 * drops two slabs, and a door or tall plant drops once, from its bottom
 * half. Chance drops, like saplings from leaves, are for later.
 */
class Drops(items: List<ItemDefinition>, creative: CreativeInventory, private val blocks: BlockPalette) {
    private val items = items.associateBy { it.name }
    private val placing = creative.items.map { it.stack }.filter { it.block != null }.distinctBy { it.item.name }.associateBy { it.item.name }

    /** What [state] drops, which a player holding something that harvests it gets. */
    fun of(state: BlockState): List<ItemStack> {
        if (state.properties[UPPER_BLOCK] == 1.toByte() || dropsNothing(state.name)) return emptyList()
        val (name, count) = DROPS_INSTEAD[state.name] ?: singleSlabOf(state.name)?.let { it to 2 } ?: (state.name to 1)
        val stack = placing[name] ?: items[name]?.let { ItemStack(it, block = blocks.defaultOf(name)) } ?: return emptyList()
        return listOf(stack.copy(count = count))
    }

    private fun dropsNothing(name: String) = name in DROPS_NOTHING || name.endsWith("_leaves") ||
        ((name.endsWith("glass") || name.endsWith("glass_pane")) && name != "minecraft:tinted_glass")

    /** The slab two of make a double slab called [name], or null if it isn't one. */
    private fun singleSlabOf(name: String): String? = when {
        name.endsWith("double_cut_copper_slab") -> name.replace("double_cut_copper_slab", "cut_copper_slab")
        name.endsWith("_double_slab") -> name.removeSuffix("_double_slab") + "_slab"
        else -> null
    }
}
