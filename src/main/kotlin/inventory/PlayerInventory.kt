package org.koremc.inventory

import org.koremc.protocol.ContainerName
import org.koremc.registry.ItemStack

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
