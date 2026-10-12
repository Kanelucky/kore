package org.koremc.inventory

import org.koremc.protocol.ContainerName
import org.koremc.registry.ItemStack
import org.koremc.registry.sameKindAs

/** A stack in a slot, with the ID the server gave it. The client names stacks by these IDs in its requests. */
data class SlotStack(val stack: ItemStack, val id: Int)

/** A fixed number of slots, each empty or holding a stack. */
class Container(val size: Int) {
    private val slots = arrayOfNulls<SlotStack>(size)

    operator fun get(slot: Int): SlotStack? = slots[slot]

    operator fun set(slot: Int, stack: SlotStack?) {
        slots[slot] = stack
    }
}

/** A slot in one of a player's containers. */
data class Location(val container: Container, val slot: Int)

/** How many slots the hotbar has: the first of the player's items. */
const val HOTBAR_SIZE = 9

/** Where the creative inventory puts the stack a player picks, for them to move from there. */
const val CREATED_OUTPUT_SLOT = 50

/**
 * A player's items: the hotbar and the rest of their inventory, the
 * offhand and armour, and the slots the inventory screen itself uses, like
 * the cursor and the created output.
 */
class PlayerInventory {
    /** The hotbar comes first, then the rest of the inventory, up to slot 35. */
    val items = Container(36)
    val offhand = Container(1)
    val armour = Container(4)
    /** The cursor is slot 0, the crafting grid sits from 28, and the created output is [CREATED_OUTPUT_SLOT]. */
    val ui = Container(CREATED_OUTPUT_SLOT + 1)

    private var nextStackId = 1

    /** An ID for a new stack, which the client names it by from then on. */
    fun newStackId() = nextStackId++

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
            ContainerName.CURSOR, ContainerName.CREATED_OUTPUT, ContainerName.CRAFTING_INPUT -> Location(ui, slot)
            else -> return null
        }
        return location.takeIf { it.slot in 0 until it.container.size }
    }
}
