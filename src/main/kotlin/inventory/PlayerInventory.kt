package org.koremc.inventory

import org.koremc.protocol.ContainerName
import org.koremc.registry.ItemStack
import org.koremc.registry.sameKindAs
import org.koremc.world.fitsCartographyTable
import org.koremc.world.paysBeacon

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

/**
 * A container open besides the inventory: its slots, and which of them
 * each name the client gives them covers, like a furnace's fuel slot.
 * Each slot holds at most what [limit] says of a stack, and nothing of a
 * stack it doesn't take.
 */
class OpenContainer(
    val container: Container,
    private val names: Map<Int, IntRange>,
    val limit: (slot: Int, stack: ItemStack) -> Int = { _, stack -> stack.item.maxStackSize },
) {
    /** The slot the client means by [slot] under [name], or null if the name doesn't cover it. */
    fun slot(name: Int, slot: Int): Int? = names[name]?.takeIf { slot in it }?.let { slot }

    companion object {
        /** A container whose every slot goes by [name], like a chest's. */
        fun named(name: Int, container: Container) = OpenContainer(container, mapOf(name to (0 until container.size)))
    }
}

/**
 * A block a player crafts at, whose screen uses slots of the player's own
 * that the inventory screen doesn't: each name the client gives them, and
 * the slot it is. The items left there go back among the player's when
 * the screen closes.
 */
enum class Workstation(val slots: Map<Int, Int>) {
    CRAFTING_TABLE(emptyMap()),
    STONECUTTER(mapOf(ContainerName.STONECUTTER_INPUT to 3)),
    SMITHING_TABLE(mapOf(ContainerName.SMITHING_TABLE_INPUT to 51, ContainerName.SMITHING_TABLE_MATERIAL to 52, ContainerName.SMITHING_TABLE_TEMPLATE to 53)),
    ANVIL(mapOf(ContainerName.ANVIL_INPUT to 1, ContainerName.ANVIL_MATERIAL to 2)),
    GRINDSTONE(mapOf(ContainerName.GRINDSTONE_INPUT to 16, ContainerName.GRINDSTONE_ADDITIONAL to 17)),
    LOOM(mapOf(ContainerName.LOOM_INPUT to 9, ContainerName.LOOM_DYE to 10, ContainerName.LOOM_MATERIAL to 11)),
    ENCHANTING_TABLE(mapOf(ContainerName.ENCHANTING_INPUT to ENCHANTING_INPUT_SLOT, ContainerName.ENCHANTING_MATERIAL to ENCHANTING_LAPIS_SLOT)),
    BEACON(mapOf(ContainerName.BEACON_PAYMENT to BEACON_PAYMENT_SLOT)),
    CARTOGRAPHY_TABLE(mapOf(ContainerName.CARTOGRAPHY_INPUT to CARTOGRAPHY_INPUT_SLOT, ContainerName.CARTOGRAPHY_ADDITIONAL to CARTOGRAPHY_ADDITIONAL_SLOT)),
}

/** The slots of an enchanting table's item, which holds one, and its lapis lazuli, which holds nothing else. */
const val ENCHANTING_INPUT_SLOT = 14
const val ENCHANTING_LAPIS_SLOT = 15
private const val LAPIS_LAZULI = "minecraft:lapis_lazuli"

/** The slot of what a player pays a beacon with, which holds one of it. */
const val BEACON_PAYMENT_SLOT = 27

/** The slots of a cartography table's map, and what goes with it. */
const val CARTOGRAPHY_INPUT_SLOT = 12
const val CARTOGRAPHY_ADDITIONAL_SLOT = 13

/** How many slots the hotbar has: the first of the player's items. */
const val HOTBAR_SIZE = 9

/** Where the creative inventory and crafting put what they make, for the player to move from there. */
const val CREATED_OUTPUT_SLOT = 50

/** The highest of the slots the inventory screen and workstations use: a smithing table's template. */
private const val LAST_UI_SLOT = 53

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
    /**
     * The cursor is slot 0, the crafting grids sit from 28, and the created
     * output is [CREATED_OUTPUT_SLOT]. Workstations use others, like a
     * stonecutter's 3.
     */
    val ui = Container(LAST_UI_SLOT + 1)

    /** The container open besides the inventory, if any. */
    var opened: OpenContainer? = null

    /** The workstation open, if any. A crafting table crafts on its three by three grid rather than the inventory's two by two. */
    var workstation: Workstation? = null

    /** The slots of the crafting grid in use. */
    val craftingGrid: IntRange get() = if (workstation == Workstation.CRAFTING_TABLE) LARGE_GRID else SMALL_GRID

    /** The slots of the inventory screen and the open workstation that hold what the player put there, to give back when it closes. */
    val heldInScreen: List<Int> get() = listOf(CURSOR_SLOT) + SMALL_GRID + LARGE_GRID + workstation?.slots?.values.orEmpty()

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
            in workstation?.slots.orEmpty() -> Location(ui, slot.takeIf { it == workstation!!.slots[container] } ?: return null)
            else -> opened?.let { open -> open.slot(container, slot)?.let { Location(open.container, it) } } ?: return null
        }
        return location.takeIf { it.slot in 0 until it.container.size }
    }

    /**
     * The most of [stack] [location] can hold: a stack's worth, or less, or
     * none, in the slots of a container or workstation that hold only some
     * things. An enchanting table holds one item to enchant, and only lapis
     * lazuli beside it. A beacon holds one of what pays it, and a
     * cartography table only maps and what works them.
     */
    fun limit(location: Location, stack: ItemStack): Int {
        val open = opened
        if (open != null && location.container === open.container) return open.limit(location.slot, stack)
        val most = stack.item.maxStackSize
        if (location.container !== ui) return most
        return when (workstation to location.slot) {
            Workstation.ENCHANTING_TABLE to ENCHANTING_INPUT_SLOT -> 1
            Workstation.ENCHANTING_TABLE to ENCHANTING_LAPIS_SLOT -> if (stack.item.name == LAPIS_LAZULI) most else 0
            Workstation.BEACON to BEACON_PAYMENT_SLOT -> if (stack.paysBeacon) 1 else 0
            Workstation.CARTOGRAPHY_TABLE to CARTOGRAPHY_INPUT_SLOT, Workstation.CARTOGRAPHY_TABLE to CARTOGRAPHY_ADDITIONAL_SLOT ->
                if (stack.fitsCartographyTable) most else 0
            else -> most
        }
    }
}

/** Stack IDs counted for one inventory alone, as in tests. */
private class LocalStackIds : () -> Int {
    private var next = 1

    override fun invoke() = next++
}
