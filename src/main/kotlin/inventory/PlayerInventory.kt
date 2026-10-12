package org.koremc.inventory

import org.koremc.protocol.ContainerName
import org.koremc.registry.ItemStack
import org.koremc.registry.sameKindAs

/** A stack in a slot, with the ID the server gave it. The client names stacks by these IDs in its requests. */
data class SlotStack(val stack: ItemStack, val id: Int)

/** A fixed number of slots, each empty or holding a stack. */
open class Container(val size: Int) {
    private val slots = arrayOfNulls<SlotStack>(size)

    open operator fun get(slot: Int): SlotStack? = slots[slot]

    open operator fun set(slot: Int, stack: SlotStack?) {
        slots[slot] = stack
    }

    /** What every slot holds, in order. */
    fun contents(): List<SlotStack?> = (0 until size).map(::get)

    /** Empties every slot, returning what each held, in order, with nothing for an empty one. */
    fun empty(): List<ItemStack?> = (0 until size).map { slot -> get(slot)?.stack.also { set(slot, null) } }
}

/**
 * Two containers seen as one, the first's slots then the second's, as a
 * double chest's window shows its two chests.
 */
class PairedContainer(private val first: Container, private val second: Container) : Container(first.size + second.size) {
    override fun get(slot: Int) = if (slot < first.size) first[slot] else second[slot - first.size]

    override fun set(slot: Int, stack: SlotStack?) {
        if (slot < first.size) first[slot] = stack else second[slot - first.size] = stack
    }
}

/** A slot in one of a player's containers. */
data class Location(val container: Container, val slot: Int)

/** How many slots the hotbar has: the first of the player's items. */
const val HOTBAR_SIZE = 9

/** Where the creative inventory and crafting put what they make, for the player to move from there. */
const val CREATED_OUTPUT_SLOT = 50

/** The slots of the cursor, and of the crafting grids: the inventory's two by two, then a crafting table's three by three. */
const val CURSOR_SLOT = 0
val SMALL_GRID = 28..31
val LARGE_GRID = 32..40

/**
 * A player's items: the hotbar and the rest of their inventory, the
 * offhand and armour, and the slots the inventory screen itself uses, like
 * the cursor, the crafting grid and the created output. While a container
 * is open, its slots are the player's to use too. Every stack gets an ID
 * from [newStackIds], which a server shares among its players, so that a
 * stack moved into a chest another player sees keeps an ID only it has.
 */
class PlayerInventory(private val newStackIds: () -> Int = LocalStackIds()) {
    /** The hotbar comes first, then the rest of the inventory, up to slot 35. */
    val items = Container(36)
    val offhand = Container(1)
    val armour = Container(4)
    /** The cursor is slot 0, the crafting grids sit from 28, and the created output is [CREATED_OUTPUT_SLOT]. */
    val ui = Container(CREATED_OUTPUT_SLOT + 1)

    /** The container open besides the inventory, if any, under the name the client gives its slots. */
    var opened: Pair<Int, Container>? = null

    /** Whether a crafting table is open, which crafts on its three by three grid rather than the inventory's two by two. */
    var craftingTable = false

    /** The slots of the crafting grid in use. */
    val craftingGrid: IntRange get() = if (craftingTable) LARGE_GRID else SMALL_GRID

    /** An ID for a new stack, which the client names it by from then on. */
    fun newStackId() = newStackIds()

    /**
     * Puts [stack] among the player's items, as picking it up does: onto
     * stacks of the same kind first, then into empty slots, the hotbar's
     * first each time. Returns the slots that changed, and how many items
     * didn't fit. A stack keeps its ID as it grows, and one in an empty slot
     * gets a new one.
     */
    fun add(stack: ItemStack): Pair<List<Int>, Int> {
        var left = stack.count
        val changed = mutableListOf<Int>()
        val limit = stack.item.maxStackSize
        for (slot in 0 until items.size) {
            val there = items[slot]?.takeIf { it.stack.sameKindAs(stack) && it.stack.count < limit } ?: continue
            val moving = minOf(left, limit - there.stack.count)
            items[slot] = there.copy(stack = there.stack.copy(count = there.stack.count + moving))
            changed += slot
            left -= moving
            if (left == 0) return changed to 0
        }
        for (slot in 0 until items.size) {
            if (items[slot] != null) continue
            val moving = minOf(left, limit)
            items[slot] = SlotStack(stack.copy(count = moving), newStackId())
            changed += slot
            left -= moving
            if (left == 0) break
        }
        return changed to left
    }

    /**
     * Takes [count] of the items in [slot] away, as placing a block or
     * dropping them does. What's left keeps its stack's ID, as the client
     * expects.
     */
    fun remove(slot: Int, count: Int = 1) {
        val there = items[slot] ?: return
        items[slot] = if (there.stack.count > count) there.copy(stack = there.stack.copy(count = there.stack.count - count)) else null
    }

    /**
     * The slot the client means by [slot] of [container], or null for a
     * container or slot Kore doesn't keep. The hotbar, the inventory and the
     * two together are names for the same slots.
     */
    fun locate(container: Int, slot: Int): Location? {
        val location = when (container) {
            ContainerName.HOTBAR, ContainerName.INVENTORY, ContainerName.HOTBAR_AND_INVENTORY -> Location(items, slot)
            ContainerName.OFFHAND -> Location(offhand, 0) // Whichever slot the client names, there's only the one
            ContainerName.ARMOR -> Location(armour, slot)
            ContainerName.CURSOR, ContainerName.CREATED_OUTPUT -> Location(ui, slot)
            ContainerName.CRAFTING_INPUT -> Location(ui, slot.takeIf { it in craftingGrid } ?: return null)
            else -> Location(opened?.takeIf { it.first == container }?.second ?: return null, slot)
        }
        return location.takeIf { it.slot in 0 until it.container.size }
    }
}

/** Stack IDs counted for one inventory alone, as in tests. */
private class LocalStackIds : () -> Int {
    private var next = 1

    override fun invoke() = next++
}
