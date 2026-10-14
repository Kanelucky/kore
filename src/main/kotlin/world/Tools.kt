package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

/**
 * What becomes of what a player used on a block: nothing, one of it used
 * up, as honeycomb is, one use of it worn off, as a hoe's is, one of it
 * swapped for what the player's handed, as a bucket emptied is, or it
 * changed into what they're handed, as leather dyed is, in creative too.
 */
enum class HeldAfter { KEPT, USED_UP, WORN, SWAPPED, CHANGED }

/**
 * What using what a player holds on a block did: where blocks, or the
 * data they keep, changed, the sound it made, what became of the held
 * item, and how many of it, items that came out of the block, to drop
 * beside it, or at [dropAt], and one handed to the player, like a flower
 * out of a pot, the notes it played, the songs it started or stopped, and
 * what else happened there.
 */
class ItemUsedOn(
    val changed: List<BlockPos>,
    val sound: BlockSound? = null,
    val held: HeldAfter = HeldAfter.KEPT,
    val dropped: List<ItemStack> = emptyList(),
    val given: ItemStack? = null,
    val events: List<WorldEvent> = emptyList(),
    val count: Int = 1,
    val dropAt: BlockPos? = null,
    val notes: List<NotePlayed> = emptyList(),
    val songs: List<SongChange> = emptyList(),
)

/** The sound a block makes as something's used on it, by its own sound set: grass for grass, wood for logs, and so on. */
private const val USED_ON = "item.use.on"

/** Blocks a hoe tills into farmland, as long as there's air above them. */
private val TILLED = setOf("minecraft:grass_block", "minecraft:dirt", "minecraft:grass_path")

/** Blocks a hoe turns into plain dirt, coarse dirt with air above it, and rooted dirt, which gives up its roots, whatever's above. */
private const val COARSE_DIRT = "minecraft:coarse_dirt"
private const val ROOTED_DIRT = "minecraft:dirt_with_roots"

/** Blocks a shovel flattens into a path, with air above them. */
private val FLATTENED = setOf("minecraft:grass_block", "minecraft:dirt", COARSE_DIRT, "minecraft:mycelium", "minecraft:podzol", ROOTED_DIRT)

private const val FARMLAND = "minecraft:farmland"
private const val PATH = "minecraft:grass_path"
private const val DIRT = "minecraft:dirt"
private const val PUMPKIN = "minecraft:pumpkin"
private const val CARVED_PUMPKIN = "minecraft:carved_pumpkin"

/** How copper weathers, a stage at a time, by how its name starts. */
private val WEATHERING = listOf("", "exposed_", "weathered_", "oxidized_")
private const val WAXED = "waxed_"

/** Copper of some kind, [base], as weathered as [stage] says, and [waxed] or not. */
private data class Copper(val base: String, val stage: Int, val waxed: Boolean) {
    /** The game's name for this copper: plain copper is "copper_block" until it's waxed or weathered. */
    val name: String
        get() = "minecraft:" + (if (waxed) WAXED else "") + WEATHERING[stage] + if (base == "copper" && stage == 0 && !waxed) "copper_block" else base
}

/** [name] as copper, or null for a block that isn't copper, or a lightning rod, which weathers as copper does. */
private fun copperOf(name: String): Copper? {
    var rest = name.removePrefix("minecraft:")
    val waxed = rest.startsWith(WAXED)
    rest = rest.removePrefix(WAXED)
    val stage = WEATHERING.indexOfLast { it.isNotEmpty() && rest.startsWith(it) }.coerceAtLeast(0)
    rest = rest.removePrefix(WEATHERING[stage])
    if (rest == "copper_block") rest = "copper"
    return Copper(rest, stage, waxed).takeIf { "copper" in rest || rest == "lightning_rod" }
}

/**
 * What the copper block [name] is underneath however weathered or waxed,
 * so that a copper chest, say, is the same chest waxed. Null for a block
 * that isn't copper.
 */
internal fun copperKind(name: String): String? = copperOf(name)?.let { Copper(it.base, 0, waxed = false).name }

/**
 * Uses the tool, or honeycomb, [held] on [face] of the block at [pos], as
 * a player facing [facing] does, the game's way, if it does anything to
 * it, and returns what it did, or null if it does nothing:
 *
 * - A hoe tills grass, dirt and paths into farmland, and coarse dirt into
 *   dirt, with air above, and rooted dirt into dirt whatever's above,
 *   which gives up its roots.
 * - A shovel flattens grass and dirt of every kind into a path, with air
 *   above.
 * - An axe strips logs, wood, stems and hyphae, scrapes a stage of
 *   weathering off copper, or its wax.
 * - Honeycomb waxes copper, so it stops weathering, using itself up.
 * - Shears carve a pumpkin, turning its face to the face clicked, or
 *   towards the player from above or below, and a seed drops.
 *
 * Tools wear a use each time. None of it works from below the block.
 */
fun FlatWorld.useToolOn(pos: BlockPos, face: Face, held: ItemStack, facing: Face, items: ItemStacks): ItemUsedOn? {
    val state = stateAt(pos) ?: return null
    val tool = held.item.name
    val open = face != Face.DOWN && stateAt(pos.side(Face.UP))?.name == AIR
    return when {
        tool.endsWith("_hoe") -> when {
            state.name in TILLED && open -> reshape(pos, blocks.defaultOf(FARMLAND))
            state.name == COARSE_DIRT && open -> reshape(pos, blocks.defaultOf(DIRT))
            state.name == ROOTED_DIRT -> reshape(pos, blocks.defaultOf(DIRT))?.let {
                ItemUsedOn(it.changed, it.sound, it.held, dropped = listOfNotNull(items.of("minecraft:hanging_roots")))
            }
            else -> null
        }
        tool.endsWith("_shovel") -> if (state.name in FLATTENED && open) reshape(pos, blocks.defaultOf(PATH)) else null
        tool.endsWith("_axe") -> {
            val stripped = blocks.find(state.name.replace("minecraft:", "minecraft:stripped_"), state.properties)
            val copper = copperOf(state.name)
            when {
                !state.name.contains("stripped_") && stripped != null -> reshape(pos, stripped)
                copper != null && copper.waxed -> reshape(pos, blocks.find(copper.copy(waxed = false).name, state.properties), "copper.wax.off")
                copper != null && copper.stage > 0 -> reshape(pos, blocks.find(copper.copy(stage = copper.stage - 1).name, state.properties), "scrape")
                else -> null
            }
        }
        tool == "minecraft:honeycomb" -> {
            val copper = copperOf(state.name)?.takeUnless { it.waxed } ?: return null
            reshape(pos, blocks.find(copper.copy(waxed = true).name, state.properties), "copper.wax.on", HeldAfter.USED_UP)
        }
        tool == "minecraft:shears" && state.name == PUMPKIN -> {
            val front = if (face.horizontal) face else facing.opposite
            val carved = blocks.find(CARVED_PUMPKIN, mapOf(CARDINAL to front.id)) ?: return null
            set(pos, carved)
            val seeds = items.of("minecraft:pumpkin_seeds")
            ItemUsedOn(listOf(pos), BlockSound(pos, "pumpkin.carve", -1), HeldAfter.WORN, dropped = listOfNotNull(seeds))
        }
        else -> null
    }
}

/**
 * Turns the block at [pos] into [into], if there is one, with [sound], or
 * the sound of using something on the new block, and [held] after.
 */
private fun FlatWorld.reshape(pos: BlockPos, into: BlockState?, sound: String? = null, held: HeldAfter = HeldAfter.WORN): ItemUsedOn? {
    into ?: return null
    set(pos, into)
    return ItemUsedOn(listOf(pos), if (sound != null) BlockSound(pos, sound, -1) else BlockSound(pos, USED_ON, into.networkId), held)
}
