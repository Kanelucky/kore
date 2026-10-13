package org.koremc.world

import org.koremc.inventory.Container
import org.koremc.inventory.SlotStack
import org.koremc.protocol.ContainerName
import org.koremc.protocol.ContainerType
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipes
import org.koremc.registry.sameKindAs
import org.koremc.registry.smeltingExperience

/**
 * The three kinds of furnace: each cooks by its own recipes, and a blast
 * furnace or smoker twice as fast as a furnace, burning its fuel twice as
 * fast too. Each shows as its [litBlock] while its fuel burns. The client
 * names a furnace's slot for what it cooks [ingredient], whichever kind.
 */
enum class FurnaceKind(val block: String, val litBlock: String, val recipes: String, val window: Int, val ingredient: Int, val cookTicks: Int) {
    FURNACE("minecraft:furnace", "minecraft:lit_furnace", "furnace", ContainerType.FURNACE, ContainerName.FURNACE_INGREDIENT, 200),
    BLAST_FURNACE("minecraft:blast_furnace", "minecraft:lit_blast_furnace", "blast_furnace", ContainerType.BLAST_FURNACE, ContainerName.BLAST_FURNACE_INGREDIENT, 100),
    SMOKER("minecraft:smoker", "minecraft:lit_smoker", "smoker", ContainerType.SMOKER, ContainerName.SMOKER_INGREDIENT, 100),
    ;

    /** How many ticks of a furnace's fuel burn in one of this kind's. */
    val burnSpeed get() = FURNACE.cookTicks / cookTicks
}

/** The kind of furnace this is, lit or not, or null if it isn't one. */
val BlockState.furnaceKind: FurnaceKind? get() = FurnaceKind.entries.find { name == it.block || name == it.litBlock }

/** Blocks that keep items and work on them by themselves: furnaces, brewing stands, hoppers, dispensers and droppers. */
sealed class Machine(size: Int) {
    val slots = Container(size)

    /**
     * The most of [stack] [slot] holds: a stack's worth, or less, or none
     * of what it doesn't take, by [recipes] and [fuel]. Nothing goes into
     * what a furnace made, and only fuel under it. A brewing stand's bottles
     * hold one potion or bottle each, its reagent slot only what brews, and
     * its fuel slot only blaze powder. Hoppers, dispensers and droppers
     * take anything.
     */
    fun limit(slot: Int, stack: ItemStack, recipes: Recipes, fuel: Fuel): Int {
        val most = stack.item.maxStackSize
        return when (this) {
            is Furnace -> when (slot) {
                FURNACE_OUTPUT -> 0
                FURNACE_FUEL -> if (fuel.burnTicks(stack) != null) most else 0
                else -> most
            }
            is BrewingStand -> when (slot) {
                in BREWING_BOTTLES -> if (stack.item.name in BOTTLES) 1 else 0
                BREWING_REAGENT -> if (recipes.isReagent(stack)) most else 0
                else -> if (stack.item.name == BLAZE_POWDER) most else 0
            }
            is Hopper, is Dispenser -> most
        }
    }
}

// A furnace's slots.
const val FURNACE_INPUT = 0
const val FURNACE_FUEL = 1
const val FURNACE_OUTPUT = 2

/**
 * A furnace of [kind], and what it's doing: how long its fuel burns on
 * for, of how long it had, how far it's cooked what it's cooking, and the
 * experience what it made has earned, which goes to whoever takes it out.
 */
class Furnace(val kind: FurnaceKind) : Machine(3) {
    /** Ticks the fuel burning has left, and how many it started with. */
    var litTime = 0
        private set
    var litDuration = 0
        private set

    /** Ticks it's cooked what it's cooking for. */
    var cookTime = 0
        private set

    /** Experience earned by what it's made, until someone takes it. */
    var experience = 0f
        private set

    val lit get() = litTime > 0

    /** Takes out the experience it's stored, to give to whoever took what it made. */
    fun takeExperience(): Float = experience.also { experience = 0f }

    /**
     * Burns and cooks for a tick, as the game's furnaces do. While fuel
     * burns, and there's something to cook that what it makes has room
     * for, cooking goes on, and once it's cooked long enough what it
     * cooked turns into what it makes. When the fuel burns out with more to
     * cook, the next fuel goes on. With nothing burning, cooking cools back
     * off, two ticks at a time; with nothing to cook, it starts over.
     * Returns whether it lit or went out.
     */
    internal fun tick(recipes: Recipes, fuel: Fuel, newStackId: () -> Int): Boolean {
        val wasLit = lit
        if (lit) litTime--
        val input = slots[FURNACE_INPUT]
        val fuelStack = slots[FURNACE_FUEL]
        if (lit || (input != null && fuelStack != null)) {
            val result = input?.let { recipes.cook(kind.recipes, it.stack) }
            val cooks = result != null && hasRoomFor(result)
            if (!lit && cooks && fuelStack != null) {
                fuel.burnTicks(fuelStack.stack)?.let { burn ->
                    litTime = burn / kind.burnSpeed
                    litDuration = litTime
                    burnUp(fuelStack, fuel, newStackId)
                }
            }
            if (lit && cooks) {
                if (++cookTime >= kind.cookTicks) {
                    cookTime = 0
                    cook(input!!, result!!, newStackId)
                }
            } else {
                cookTime = 0
            }
        } else if (cookTime > 0) {
            cookTime = maxOf(0, cookTime - 2)
        }
        return wasLit != lit
    }

    /** Whether what it's made so far leaves room for [result]. */
    private fun hasRoomFor(result: ItemStack): Boolean {
        val made = slots[FURNACE_OUTPUT]?.stack ?: return true
        return made.sameKindAs(result) && made.count + result.count <= made.item.maxStackSize
    }

    /** Uses up one of what's in [burning], leaving behind what held it, like a lava bucket's bucket. */
    private fun burnUp(burning: SlotStack, fuel: Fuel, newStackId: () -> Int) {
        val leftover = fuel.leftover(burning.stack)
        slots[FURNACE_FUEL] = when {
            leftover != null -> SlotStack(leftover, newStackId())
            burning.stack.count > 1 -> burning.copy(stack = burning.stack.copy(count = burning.stack.count - 1))
            else -> null
        }
    }

    /** Turns one of [input] into [result]. */
    private fun cook(input: SlotStack, result: ItemStack, newStackId: () -> Int) {
        slots[FURNACE_INPUT] = if (input.stack.count > 1) input.copy(stack = input.stack.copy(count = input.stack.count - 1)) else null
        val made = slots[FURNACE_OUTPUT]
        slots[FURNACE_OUTPUT] = made?.copy(stack = made.stack.copy(count = made.stack.count + result.count)) ?: SlotStack(result, newStackId())
        experience += smeltingExperience(result) * result.count
    }
}
