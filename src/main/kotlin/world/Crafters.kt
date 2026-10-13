package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.gridUses
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipes
import org.koremc.registry.ShapedRecipe
import org.koremc.registry.ShapelessRecipe
import org.koremc.registry.sameKindAs

internal const val CRAFTER = "minecraft:crafter"

/** How many slots a crafter has: a crafting grid's three by three. */
const val CRAFTER_SLOTS = 9

/** A crafter's state for showing it's just crafted, and its front and top, as in "north_up". */
private const val CRAFTING = "crafting"
private const val ORIENTATION = "orientation"

/** How long after power reaches it a crafter crafts, in ticks. */
internal const val CRAFT_DELAY = 4

/** How long a crafter shows it's just crafted, in ticks. */
private const val CRAFTING_SHOWN = 6

/** The sounds of a crafter crafting, and finding nothing to craft. */
private const val CRAFTED = "crafter.craft"
private const val NOTHING_CRAFTED = "crafter.fail"

/**
 * A crafter: the items laid out in its grid, the slots players have
 * switched off, which take nothing, and whether it's about to craft.
 */
class Crafter : Machine(CRAFTER_SLOTS) {
    /** The slots switched off, a bit for each, the first slot's lowest. */
    var disabled = 0
        internal set

    /** Ticks until it crafts, now that power has reached it, or 0 if it isn't about to. */
    internal var craftIn = 0

    /** Ticks it goes on showing it's just crafted. */
    internal var shownCrafting = 0

    fun isDisabled(slot: Int) = disabled and (1 shl slot) != 0

    /** How many of its slots are full or switched off, which a comparator reads. */
    val taken get() = (0 until CRAFTER_SLOTS).count { slots[it] != null || isDisabled(it) }

    /** Its block entity, as the client takes it: which slots are switched off. */
    fun data(): NbtMap = NbtMap.builder().putString("id", "Crafter").putShort("disabled_slots", disabled.toShort()).build()

    /**
     * The slots [stack] goes into, in the order it fills them, as a hopper
     * or dropper fills a crafter: an empty slot that's on, in reading order,
     * or once there's none, the smallest stack of its kind.
     */
    internal fun fillOrder(stack: ItemStack): List<Int> {
        val open = (0 until CRAFTER_SLOTS).filter { !isDisabled(it) }
        val empty = open.filter { slots[it] == null }
        if (empty.isNotEmpty()) return empty
        return open.filter { slots[it]!!.stack.sameKindAs(stack) }.sortedBy { slots[it]!!.stack.count }
    }
}

val BlockState.isCrafter get() = name == CRAFTER

/**
 * Switches the empty [slot] of the crafter at [pos] off, if [disabled], or
 * back on, as a player clicking it does. Returns whether it changed.
 */
fun FlatWorld.toggleCrafterSlot(pos: BlockPos, slot: Int, disabled: Boolean): Boolean {
    val crafter = crafterAt(pos) ?: return false
    if (slot !in 0 until CRAFTER_SLOTS || crafter.slots[slot] != null || crafter.isDisabled(slot) == disabled) return false
    crafter.disabled = if (disabled) crafter.disabled or (1 shl slot) else crafter.disabled and (1 shl slot).inv()
    setBlockEntity(pos, crafter.data())
    return true
}

/**
 * Works the [crafter] at [pos], [state], a tick: once the power that
 * reached it has had time to, it crafts, and a moment after, stops showing
 * it has. Its sound goes in [sounds]. Returns whether its state changed.
 */
internal fun FlatWorld.tickCrafter(pos: BlockPos, crafter: Crafter, state: BlockState, recipes: Recipes, fuel: Fuel, sounds: MutableList<BlockSound>): Boolean {
    var shown = state
    if (crafter.shownCrafting > 0 && --crafter.shownCrafting == 0) shown = blocks.find(CRAFTER, shown.properties + (CRAFTING to bit(false))) ?: shown
    if (crafter.craftIn > 0 && --crafter.craftIn == 0) {
        if (craft(pos, crafter, shown, recipes, fuel)) {
            sounds += BlockSound(pos, CRAFTED, -1)
            crafter.shownCrafting = CRAFTING_SHOWN
            shown = blocks.find(CRAFTER, shown.properties + (CRAFTING to bit(true))) ?: shown
        } else {
            sounds += BlockSound(pos, NOTHING_CRAFTED, -1)
        }
    }
    if (shown == state) return false
    set(pos, shown)
    return true
}

/**
 * Crafts once from what the [crafter] at [pos], [state], has laid out, by
 * the first crafting table recipe, by priority, its grid holds, using up
 * what that takes. What it makes goes out of its front, into the
 * container there, if that takes it, or flying out if not, and what the
 * recipe gives back besides, like the buckets a cake leaves, after it.
 * Returns whether it made anything.
 */
private fun FlatWorld.craft(pos: BlockPos, crafter: Crafter, state: BlockState, recipes: Recipes, fuel: Fuel): Boolean {
    val grid = (0 until CRAFTER_SLOTS).map { crafter.slots[it]?.stack }
    val (recipe, uses) = recipes.crafting.firstNotNullOfOrNull { recipe -> recipes.gridUses(recipe, grid, 1)?.let { recipe to it } } ?: return false
    val outputs = when (recipe) {
        is ShapelessRecipe -> recipe.outputs.let { outputs ->
            // A recipe that keeps data, like dyeing a shulker box, passes on what the item it changes held.
            val kept = if (recipe.keepsData) uses.keys.firstNotNullOfOrNull { grid[it]?.nbt?.takeIf(NbtMap::isNotEmpty) } else null
            outputs.map { it.copy(nbt = kept ?: it.nbt) }
        }
        is ShapedRecipe -> recipe.outputs
        else -> return false
    }
    for ((slot, used) in uses) crafter.slots[slot] = crafter.slots[slot]!!.less(used)
    val front = Face.entries.find { it.id == (state.properties[ORIENTATION] as? String)?.substringBefore('_') } ?: Face.NORTH
    for (output in outputs) {
        val target = hopperReach(pos.side(front), front.opposite, recipes, fuel)
        val passed = target?.put(output, output.count, ::newStackId) ?: 0
        if (passed < output.count) dropOut(pos, front, output.copy(count = output.count - passed))
    }
    return true
}
