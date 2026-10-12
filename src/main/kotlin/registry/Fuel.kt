package org.koremc.registry

/**
 * How long items burn in a furnace, in ticks, by name. The game's data has
 * no list of fuel, so this is the game's own, item by item, for those not
 * covered by the rules for wooden things in [Fuel].
 */
private val BURN_TICKS = mapOf(
    "lava_bucket" to 20000, "coal_block" to 16000, "dried_kelp_block" to 4000, "blaze_rod" to 2400, "coal" to 1600, "charcoal" to 1600,
    "bow" to 300, "crossbow" to 300, "fishing_rod" to 300, "ladder" to 300, "crafting_table" to 300, "cartography_table" to 300,
    "fletching_table" to 300, "smithing_table" to 300, "loom" to 300, "bookshelf" to 300, "chiseled_bookshelf" to 300, "lectern" to 300,
    "composter" to 300, "barrel" to 300, "chest" to 300, "trapped_chest" to 300, "jukebox" to 300, "noteblock" to 300,
    "daylight_detector" to 300, "brown_mushroom_block" to 300, "red_mushroom_block" to 300, "mushroom_stem" to 300, "beehive" to 300,
    "bee_nest" to 300, "mangrove_roots" to 300, "banner" to 300, "bamboo_block" to 300, "stripped_bamboo_block" to 300,
    "bamboo_mosaic" to 300, "bamboo_mosaic_stairs" to 300, "bamboo_mosaic_slab" to 150,
    // Oak's own names for these, which the other woods name after themselves.
    "fence_gate" to 300, "trapdoor" to 300, "wooden_pressure_plate" to 300, "wooden_door" to 200, "wooden_button" to 100,
    "stick" to 100, "bowl" to 100, "dead_bush" to 100, "azalea" to 100, "flowering_azalea" to 100, "mangrove_propagule" to 100,
    "bamboo" to 50, "scaffolding" to 50,
)

/** How long each wooden thing burns, by what its name ends in after its wood. */
private val WOODEN_BURN_TICKS = listOf(
    "_planks" to 300, "_stairs" to 300, "_slab" to 150, "_fence_gate" to 300, "_fence" to 300, "_trapdoor" to 300, "_door" to 200,
    "_button" to 100, "_pressure_plate" to 300, "_hanging_sign" to 200, "_sign" to 200, "_chest_boat" to 1200, "_boat" to 1200,
    "_chest_raft" to 1200, "_raft" to 1200, "_shelf" to 300, "_sapling" to 100,
)

/** Woods from the Nether, which don't burn. */
private val NETHER_WOODS = setOf("crimson", "warped")

/**
 * How long items burn in a furnace, in ticks. A data-driven item says for
 * itself. Otherwise, coal, blaze rods, lava and the like burn as the game
 * has them, and so does anything wooden from the Overworld: logs, planks
 * and what's made of them, which [recipes]' item tags help find. Wool
 * burns, and its carpets for a little less. Lava leaves its [bucket].
 */
class Fuel(private val recipes: Recipes, private val bucket: ItemStack? = null) {
    /** The Overworld's woods, by the names their planks start with, longest first, so `dark_oak` comes before `oak`. */
    private val woods = recipes.tagged("minecraft:planks").map { it.substringAfter(':').removeSuffix("_planks") }
        .filter { it !in NETHER_WOODS }.sortedByDescending { it.length }

    /** The colours of wool, by the names its blocks start with, which carpets come in too. */
    private val wool = recipes.tagged("minecraft:wool").mapTo(HashSet()) { it.substringAfter(':').removeSuffix("_wool") }

    /** What each item burns for, once asked, with 0 for an item that doesn't. */
    private val known = HashMap<String, Int>()

    /** What burning [stack] leaves behind: a lava bucket's empty bucket, or nothing. */
    fun leftover(stack: ItemStack): ItemStack? = if (stack.item.name == "minecraft:lava_bucket") bucket else null

    /** How long [stack] burns, in ticks, or null if it isn't fuel. */
    fun burnTicks(stack: ItemStack): Int? = known.getOrPut(stack.item.name) { of(stack.item) ?: 0 }.takeIf { it > 0 }

    private fun of(item: ItemDefinition): Int? {
        item.components.component("minecraft:fuel")?.let { return (it.getFloat("duration") * 20).toInt() }
        val name = item.name.substringAfter(':')
        BURN_TICKS[name]?.let { return it }
        return when {
            item.name in recipes.tagged("minecraft:logs_that_burn") -> 300
            item.name in recipes.tagged("minecraft:wooden_tier") -> 200
            item.name in recipes.tagged("minecraft:wool") -> 100
            name.endsWith("_carpet") && name.removeSuffix("_carpet") in wool -> 67
            else -> woods.firstOrNull { name.startsWith(it + "_") }?.let { wood -> WOODEN_BURN_TICKS.firstOrNull { (end, _) -> name == wood + end }?.second }
        }
    }
}

/**
 * The experience smelting one item into [output] earns, which a furnace
 * stores until someone takes what it made. Glazed terracotta earns as
 * much as stone; things the game gives nothing for earn nothing.
 */
fun smeltingExperience(output: ItemStack): Float {
    val name = output.item.name.substringAfter(':')
    return SMELTING_EXPERIENCE[name] ?: if (name.endsWith("_glazed_terracotta")) 0.1f else 0f
}

private val SMELTING_EXPERIENCE = mapOf(
    "netherite_scrap" to 2f, "gold_ingot" to 1f, "diamond" to 1f, "emerald" to 1f, "green_dye" to 1f,
    "iron_ingot" to 0.7f, "copper_ingot" to 0.7f, "redstone" to 0.7f,
    "cooked_beef" to 0.35f, "cooked_porkchop" to 0.35f, "cooked_chicken" to 0.35f, "cooked_mutton" to 0.35f, "cooked_rabbit" to 0.35f,
    "cooked_cod" to 0.35f, "cooked_salmon" to 0.35f, "baked_potato" to 0.35f, "hardened_clay" to 0.35f,
    "brick" to 0.3f, "resin_brick" to 0.3f, "lapis_lazuli" to 0.2f, "quartz" to 0.2f, "charcoal" to 0.15f, "sponge" to 0.15f,
    "coal" to 0.1f, "glass" to 0.1f, "stone" to 0.1f, "smooth_stone" to 0.1f, "smooth_sandstone" to 0.1f, "smooth_red_sandstone" to 0.1f,
    "smooth_quartz" to 0.1f, "smooth_basalt" to 0.1f, "deepslate" to 0.1f, "cracked_stone_bricks" to 0.1f, "cracked_deepslate_bricks" to 0.1f,
    "cracked_deepslate_tiles" to 0.1f, "cracked_polished_blackstone_bricks" to 0.1f, "cracked_nether_bricks" to 0.1f, "netherbrick" to 0.1f,
    "dried_kelp" to 0.1f, "lime_dye" to 0.1f, "popped_chorus_fruit" to 0.1f, "iron_nugget" to 0.1f, "gold_nugget" to 0.1f,
    "copper_nugget" to 0.1f, "leaf_litter" to 0.1f,
)
