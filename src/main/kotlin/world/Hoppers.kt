package org.koremc.world

import org.koremc.inventory.Container
import org.koremc.inventory.PairedContainer
import org.koremc.inventory.SlotStack
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipes
import org.koremc.registry.sameKindAs

/** How many slots a hopper has. */
const val HOPPER_SLOTS = 5

/** How long a hopper waits after it moves items before it moves any more, in ticks: two and a half items a second. */
internal const val HOPPER_COOLDOWN = 8

internal const val HOPPER = "minecraft:hopper"
internal const val BUCKET = "minecraft:bucket"
internal const val GLASS_BOTTLE = "minecraft:glass_bottle"

/** A hopper's state for being locked by redstone, which stops it moving items. */
private const val LOCKED = "toggle_bit"

/** How far up a hopper's block the floor of its bowl is, which items land on: eleven sixteenths. */
internal const val HOPPER_BOWL = 0.6875

/** How close to a surface counts as on it, for an item resting in a hopper's bowl. */
private const val TOUCHING = 1e-3

/** A hopper, and how long it waits before it next moves items. */
class Hopper : Machine(HOPPER_SLOTS) {
    /** Ticks until it moves items again. */
    internal var cooldown = 0

    /** The tick of the world's machines it last worked on. */
    internal var tickedAt = -1L
}

/**
 * A block's slots as a hopper reaches them: those it puts items into, in
 * order, through the face it reaches them by, and those a hopper below
 * takes items out of, with what each slot holds the most of, which is none
 * of what it doesn't take. A hopper reaching another is [hopper].
 */
internal class HopperReach(
    val slots: Container,
    val into: List<Int>,
    val outOf: List<Int> = into,
    val limit: (slot: Int, stack: ItemStack) -> Int = { _, stack -> stack.item.maxStackSize },
    val canTake: (slot: Int, stack: ItemStack) -> Boolean = { _, _ -> true },
    val hopper: Hopper? = null,
) {
    /**
     * Puts up to [count] of [stack] in, a slot at a time in order: into an
     * empty slot, or onto a stack of its kind with room. Returns how many
     * went in.
     */
    fun put(stack: ItemStack, count: Int, newStackId: () -> Int): Int {
        var left = count
        for (slot in into) {
            if (left == 0) break
            val most = minOf(limit(slot, stack), stack.item.maxStackSize)
            val there = slots[slot]
            when {
                most <= 0 -> continue
                there == null -> minOf(left, most).let { slots[slot] = SlotStack(stack.copy(count = it), newStackId()); left -= it }
                there.stack.sameKindAs(stack) && there.stack.count < most -> minOf(left, most - there.stack.count).let {
                    slots[slot] = there.copy(stack = there.stack.copy(count = there.stack.count + it))
                    left -= it
                }
            }
        }
        return count - left
    }
}

/**
 * The slots of the block at [pos] a hopper reaches through the block's
 * [face], or null for a block without any it reaches, like an ender chest.
 * A hopper puts into a furnace what it cooks from above and its fuel from
 * the sides, and takes from below what it made, and the bucket lava leaves.
 * It puts into a brewing stand its reagent from above, and its bottles and
 * blaze powder from the sides, and takes the bottles from below. Shulker
 * boxes don't take shulker boxes. Chests, barrels and hoppers take
 * anything, a double chest as the one window it opens as.
 */
internal fun FlatWorld.hopperReach(pos: BlockPos, face: Face, recipes: Recipes, fuel: Fuel): HopperReach? {
    val state = stateAt(pos) ?: return null
    when (state.storage) {
        Storage.CHEST -> {
            val halves = chestHalves(pos)
            val slots = if (halves.size == 2) PairedContainer(containerAt(halves[0]), containerAt(halves[1])) else containerAt(pos)
            return HopperReach(slots, (0 until slots.size).toList())
        }
        Storage.BARREL -> return HopperReach(containerAt(pos), (0 until STORAGE_SLOTS).toList())
        Storage.SHULKER_BOX -> return HopperReach(containerAt(pos), (0 until STORAGE_SLOTS).toList(), limit = { _, stack ->
            if (stack.item.name.endsWith("shulker_box")) 0 else stack.item.maxStackSize
        })
        Storage.ENDER_CHEST -> return null
        null -> {}
    }
    val machine: Machine = furnaceAt(pos) ?: brewingStandAt(pos) ?: hopperAt(pos) ?: return null
    val limit = { slot: Int, stack: ItemStack -> machine.limit(slot, stack, recipes, fuel) }
    return when (machine) {
        is Furnace -> HopperReach(
            machine.slots,
            into = if (face == Face.UP) listOf(FURNACE_INPUT) else listOf(FURNACE_FUEL),
            outOf = listOf(FURNACE_OUTPUT, FURNACE_FUEL),
            limit = limit,
            canTake = { slot, stack -> slot != FURNACE_FUEL || stack.item.name == BUCKET },
        )
        is BrewingStand -> HopperReach(
            machine.slots,
            into = if (face == Face.UP) listOf(BREWING_REAGENT) else BREWING_BOTTLES + BREWING_FUEL,
            outOf = BREWING_BOTTLES + BREWING_REAGENT,
            limit = limit,
            canTake = { slot, stack -> slot != BREWING_REAGENT || stack.item.name == GLASS_BOTTLE },
        )
        is Hopper -> HopperReach(machine.slots, (0 until HOPPER_SLOTS).toList(), hopper = machine)
    }
}

/**
 * Works the [hopper] at [pos] for the machines' [tick], as the game's
 * hoppers do. Once its cooldown runs out, unless redstone locks it, it
 * passes one item on, from its first slot with one the block it points
 * into takes, and takes one in, from the container above it. With none
 * above, and no whole block either, it takes in the items over it instead,
 * from its bowl to the top of the block above, as many of a stack as fit.
 * Once it's moved anything, it waits eight ticks before it moves any more.
 * A hopper that was empty waits as long once one passes it an item, a tick
 * less if it's already worked this tick, so items go down a line of
 * hoppers at one pace, whichever works first.
 */
internal fun FlatWorld.tickHopper(pos: BlockPos, hopper: Hopper, tick: Long, recipes: Recipes, fuel: Fuel) {
    hopper.cooldown--
    hopper.tickedAt = tick
    if (hopper.cooldown > 0) return
    hopper.cooldown = 0
    val state = stateAt(pos) ?: return
    if (state.properties[LOCKED] == 1.toByte()) return
    val pushed = push(pos, hopper, state, tick, recipes, fuel)
    val pulled = pull(pos, hopper, recipes, fuel)
    if (pushed || pulled) hopper.cooldown = HOPPER_COOLDOWN
}

/** Passes one item from [hopper] into the block it points into, if that takes one. */
private fun FlatWorld.push(pos: BlockPos, hopper: Hopper, state: BlockState, tick: Long, recipes: Recipes, fuel: Fuel): Boolean {
    val facing = Face.of(state.properties[FACING_DIRECTION] as? Int ?: Face.DOWN.ordinal)?.takeIf { it != Face.UP } ?: Face.DOWN
    val target = hopperReach(pos.side(facing), facing.opposite, recipes, fuel) ?: return false
    val wasEmpty = target.hopper?.slots?.contents()?.all { it == null } == true
    for (slot in 0 until HOPPER_SLOTS) {
        val there = hopper.slots[slot] ?: continue
        if (target.put(there.stack, 1, ::newStackId) == 0) continue
        hopper.slots[slot] = there.less(1)
        target.hopper?.takeIf { wasEmpty }?.let { it.cooldown = HOPPER_COOLDOWN - if (it.tickedAt == tick) 1 else 0 }
        return true
    }
    return false
}

/** Takes one item into [hopper] from the container above it, or else the items lying on it. */
private fun FlatWorld.pull(pos: BlockPos, hopper: Hopper, recipes: Recipes, fuel: Fuel): Boolean {
    val own = HopperReach(hopper.slots, (0 until HOPPER_SLOTS).toList())
    val above = pos.side(Face.UP)
    val source = hopperReach(above, Face.DOWN, recipes, fuel)
    if (source != null) {
        for (slot in source.outOf) {
            val there = source.slots[slot] ?: continue
            if (!source.canTake(slot, there.stack) || own.put(there.stack, 1, ::newStackId) == 0) continue
            source.slots[slot] = there.less(1)
            return true
        }
        return false
    }
    if (stateAt(above)?.isFullCube == true) return false
    for (item in items.filter { reachesInto(it, pos) }) {
        val taken = own.put(item.stack, item.stack.count, ::newStackId)
        if (taken == 0) continue
        if (taken == item.stack.count) removeItem(item.id) else item.stack = item.stack.copy(count = item.stack.count - taken)
        return true
    }
    return false
}

/**
 * Whether [item] is where the hopper at [pos] takes it in: over the hopper,
 * from its bowl to the top of the block above. As in Bedrock, it's where
 * the item is that counts, the middle of its bottom, so one that only
 * brushes the space with its side stays out.
 */
private fun reachesInto(item: ItemEntity, pos: BlockPos): Boolean {
    val (x, y, z) = item.position
    return x >= pos.x && x < pos.x + 1 && z >= pos.z && z < pos.z + 1 && y >= pos.y + HOPPER_BOWL - TOUCHING && y < pos.y + 2
}
