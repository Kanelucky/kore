package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.ItemDefinition
import org.koremc.registry.Recipes

// A brewing stand's slots: the reagent, three bottles, and blaze powder.
const val BREWING_REAGENT = 0
val BREWING_BOTTLES = 1..3
const val BREWING_FUEL = 4

/** How long a brew takes, in ticks: twenty seconds. */
const val BREW_TICKS = 400

/** How many brews one blaze powder fuels. */
const val BREWS_PER_FUEL = 20

internal const val BREWING_STAND = "minecraft:brewing_stand"
internal const val BLAZE_POWDER = "minecraft:blaze_powder"

/** What a brewing stand's bottle slots hold. */
internal val BOTTLES = setOf("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:glass_bottle")

/**
 * The block states that show which of a brewing stand's bottles hold
 * something, by slot: the left one's, the right one's, then the middle
 * one's, as the game names them.
 */
internal val BOTTLE_BITS = mapOf(1 to "brewing_stand_slot_a_bit", 3 to "brewing_stand_slot_b_bit", 2 to "brewing_stand_slot_c_bit")

/**
 * A brewing stand, and what it's doing: how long the brew going on has
 * left, and how many more brews its blaze powder fuels.
 */
class BrewingStand : Machine(5) {
    /** Ticks left of the brew going on, or 0 for none. */
    var brewTime = 0
        private set

    /** Brews left in the blaze powder put in. */
    var fuel = 0
        private set

    /** The reagent the brew going on started with. Taking it out, or putting another in, stops the brew. */
    private var reagent: ItemDefinition? = null

    /** Picks up where it was saved: [brewTime] ticks left of a brew with what's in its reagent slot, and [fuel] brews left. */
    internal fun restore(brewTime: Int, fuel: Int) {
        this.brewTime = brewTime.coerceAtLeast(0)
        this.fuel = fuel.coerceAtLeast(0)
        if (this.brewTime > 0) reagent = slots[BREWING_REAGENT]?.stack?.item
    }

    /**
     * Brews for a tick, as the game's brewing stands do. With no fuel left,
     * a blaze powder goes on, for twenty brews. A brew starts when the
     * reagent brews into what one of the bottles holds, using a brew's
     * fuel, and takes twenty seconds. Then every bottle it brews into turns
     * into what it makes, and one reagent is used up. A brew stops if the
     * reagent changes, or brews into none of the bottles any more. Returns
     * whether a brew finished.
     */
    internal fun tick(recipes: Recipes, newStackId: () -> Int): Boolean {
        val powder = slots[BREWING_FUEL]
        if (fuel <= 0 && powder != null && powder.stack.item.name == BLAZE_POWDER) {
            fuel = BREWS_PER_FUEL
            slots[BREWING_FUEL] = powder.less(1)
        }
        val brewable = brewable(recipes)
        val ingredient = slots[BREWING_REAGENT]?.stack?.item
        if (brewTime > 0) {
            brewTime--
            if (brewTime == 0 && brewable) {
                brew(recipes, newStackId)
                return true
            }
            if (!brewable || ingredient != reagent) brewTime = 0
        } else if (brewable && fuel > 0) {
            fuel--
            brewTime = BREW_TICKS
            reagent = ingredient
        }
        return false
    }

    /** Whether the reagent brews into anything in the bottles. */
    private fun brewable(recipes: Recipes): Boolean {
        val reagent = slots[BREWING_REAGENT]?.stack ?: return false
        return BREWING_BOTTLES.any { slot -> slots[slot]?.let { recipes.brew(it.stack, reagent) } != null }
    }

    /** Brews every bottle the reagent brews into, each a new stack, and uses up one reagent. */
    private fun brew(recipes: Recipes, newStackId: () -> Int) {
        val reagent = slots[BREWING_REAGENT]!!
        for (slot in BREWING_BOTTLES) {
            val bottle = slots[slot] ?: continue
            recipes.brew(bottle.stack, reagent.stack)?.let { slots[slot] = SlotStack(it, newStackId()) }
        }
        slots[BREWING_REAGENT] = reagent.less(1)
    }

    /** Which bottle slots hold something, which the block shows. */
    val bottlesShown: Set<Int> get() = BREWING_BOTTLES.filterTo(HashSet()) { slots[it] != null }
}

/** This stack with [count] fewer, keeping its ID, or nothing if that's all of it. */
internal fun SlotStack.less(count: Int): SlotStack? = if (stack.count > count) copy(stack = stack.copy(count = stack.count - count)) else null
