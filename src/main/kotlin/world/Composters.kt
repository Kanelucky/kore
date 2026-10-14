package org.koremc.world

import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import kotlin.random.Random

internal const val COMPOSTER = "minecraft:composter"
private const val LEVEL = "composter_fill_level"

/** The level a composter is full at, waiting to be ready, and the level it's ready at, with bone meal to take. */
private const val FULL = 7
internal const val READY = 8

/** How long a full composter takes to be ready, in ticks. */
internal const val COMPOST_TICKS = 20

/** The particles that rise out of a composter as something goes in, as bone meal makes. */
private const val COMPOST_PARTICLES = 2005

internal const val BONE_MEAL = "minecraft:bone_meal"

/** What goes in a composter, by name, with the chance each one raises its level. */
private val COMPOSTABLE: Map<String, Double> = buildMap {
    fun chance(chance: Double, vararg names: String) = names.forEach { put("minecraft:$it", chance) }
    chance(
        0.3, "beetroot_seeds", "dried_kelp", "short_grass", "hanging_roots", "kelp", "mangrove_roots", "melon_seeds", "moss_carpet",
        "pink_petals", "pitcher_pod", "pumpkin_seeds", "seagrass", "small_dripleaf_block", "sweet_berries", "glow_berries",
        "torchflower_seeds", "wheat_seeds", "leaf_litter", "wildflowers", "bush", "short_dry_grass", "tall_dry_grass", "cactus_flower",
        "pale_moss_carpet", "pale_hanging_moss", "mangrove_propagule",
    )
    chance(
        0.5, "cactus", "dried_kelp_block", "azalea_leaves_flowered", "glow_lichen", "melon_slice", "nether_sprouts", "sugar_cane",
        "tall_grass", "twisting_vines", "vine", "weeping_vines", "firefly_bush",
    )
    chance(
        0.65, "apple", "azalea", "beetroot", "big_dripleaf", "carrot", "cocoa_beans", "fern", "large_fern", "dandelion", "poppy",
        "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
        "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "open_eyeblossom", "closed_eyeblossom",
        "crimson_fungus", "warped_fungus", "waterlily", "melon_block", "moss_block", "pale_moss_block", "brown_mushroom", "red_mushroom",
        "mushroom_stem", "nether_wart", "potato", "pumpkin", "carved_pumpkin", "crimson_roots", "warped_roots", "sea_pickle", "shroomlight",
        "spore_blossom", "wheat",
    )
    chance(
        0.85, "baked_potato", "bread", "cookie", "flowering_azalea", "hay_block", "brown_mushroom_block", "red_mushroom_block",
        "nether_wart_block", "warped_wart_block", "torchflower", "pitcher_plant",
    )
    chance(1.0, "cake", "pumpkin_pie")
}

/** The chance [item] raises a composter's level, or null if it doesn't go in one: leaves and saplings besides those above. */
private fun compostChance(item: String): Double? = COMPOSTABLE[item] ?: when {
    item.endsWith("_leaves") || item.endsWith("_sapling") -> 0.3
    else -> null
}

val BlockState.isComposter get() = name == COMPOSTER

private val BlockState.compostLevel get() = properties[LEVEL] as? Int ?: 0

/** The composter [state] at [level]. */
private fun FlatWorld.withLevel(pos: BlockPos, state: BlockState, level: Int) {
    blocks.find(COMPOSTER, state.properties + (LEVEL to level))?.let { set(pos, it) }
}

/**
 * Uses [held] on the composter at [pos], [state], as a player clicking it
 * does. One of anything that composts goes in, a chance, by what it is,
 * of raising the composter a level, with particles either way; at the
 * seventh level it's full, and a second later it's ready. A ready one gives
 * up bone meal out of its top, made with [items], whatever's held, and
 * starts again. Null for a click with nothing that composts, but for a
 * full one waiting, which takes the click and does nothing.
 */
fun FlatWorld.useComposter(pos: BlockPos, state: BlockState, held: ItemStack?, items: ItemStacks, random: Random = Random.Default): ItemUsedOn? {
    val level = state.compostLevel
    if (level == READY) {
        withLevel(pos, state, 0)
        return ItemUsedOn(listOf(pos), BlockSound(pos, "block.composter.empty", -1), dropped = listOfNotNull(items.of(BONE_MEAL)), dropAt = pos.side(Face.UP))
    }
    val chance = held?.let { compostChance(it.item.name) } ?: return null
    if (level >= FULL) return ItemUsedOn(emptyList())
    val raised = compost(pos, state, chance, random)
    val sound = if (raised) "block.composter.fill_success" else "block.composter.fill"
    return ItemUsedOn(listOfNotNull(pos.takeIf { raised }), BlockSound(pos, sound, -1), HeldAfter.USED_UP, events = listOf(WorldEvent(pos, COMPOST_PARTICLES, 0)))
}

/**
 * Puts something into the composter at [pos], [state], which raises its
 * level by [chance], readying it a second after it's full. Returns whether
 * its level rose.
 */
private fun FlatWorld.compost(pos: BlockPos, state: BlockState, chance: Double, random: Random): Boolean {
    val level = state.compostLevel
    if (level >= FULL || random.nextDouble() >= chance) return false
    withLevel(pos, state, level + 1)
    if (level + 1 == FULL) schedule(pos, COMPOST_TICKS, BlockUpdate.COMPOST)
    return true
}

/** Readies the full composter at [pos], [state], its time up, with its sound, which goes in [sounds]. Returns whether it was full. */
internal fun FlatWorld.readyCompost(pos: BlockPos, state: BlockState, sounds: MutableList<BlockSound>): Boolean {
    if (!state.isComposter || state.compostLevel != FULL) return false
    withLevel(pos, state, READY)
    sounds += BlockSound(pos, "block.composter.ready", -1)
    return true
}

/**
 * Puts one of [stack] into the composter at [pos] from the hopper above
 * it, if it composts and the composter isn't full, with its sound, which
 * goes in [sounds]. Returns whether it went in, and where the composter's
 * level rose, in [changed].
 */
internal fun FlatWorld.compostFromHopper(pos: BlockPos, stack: ItemStack, changed: MutableList<BlockPos>, sounds: MutableList<BlockSound>): Boolean {
    val state = stateAt(pos)?.takeIf { it.isComposter && it.compostLevel < FULL } ?: return false
    val chance = compostChance(stack.item.name) ?: return false
    val raised = compost(pos, state, chance, Random.Default)
    if (raised) changed += pos
    sounds += BlockSound(pos, if (raised) "block.composter.fill_success" else "block.composter.fill", -1)
    return true
}

/** Whether the composter at [pos] is ready, with bone meal to take out. */
internal fun FlatWorld.compostReady(pos: BlockPos) = stateAt(pos)?.let { it.isComposter && it.compostLevel == READY } == true

/**
 * Empties the ready composter at [pos], as the hopper below takes out its
 * bone meal, with its sound, which goes in [sounds], and where it changed
 * in [changed].
 */
internal fun FlatWorld.emptyCompost(pos: BlockPos, changed: MutableList<BlockPos>, sounds: MutableList<BlockSound>) {
    val state = stateAt(pos)?.takeIf { it.isComposter && it.compostLevel == READY } ?: return
    withLevel(pos, state, 0)
    changed += pos
    sounds += BlockSound(pos, "block.composter.empty", -1)
}
