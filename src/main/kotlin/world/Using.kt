package org.koremc.world

import org.koremc.registry.BlockState

private const val LEVER = "minecraft:lever"
private const val OPEN = "open_bit"
private const val PRESSED = "button_pressed_bit"
private const val CARDINAL = "minecraft:cardinal_direction"

/** How long a button stays pressed, in ticks: a second for stone ones, half as long again for wooden ones. */
private const val STONE_BUTTON_TICKS = 20
private const val WOODEN_BUTTON_TICKS = 30

/** Doors and trapdoors that only redstone opens, not a hand. */
private val OPENED_BY_REDSTONE = setOf("minecraft:iron_door", "minecraft:iron_trapdoor")

/**
 * What using a block did: where blocks changed, and the sound it made, by
 * the game's name for it. A pressed button also says how many ticks until
 * it pops back out.
 *
 * The client plays a sound as the block it names makes it, from the
 * block's sound set: a lever's clicks are its own, a wooden button's are
 * wood, and so on. With no block named, the client plays a plain click.
 */
data class Used(
    val changed: List<BlockPos>,
    val sound: String? = null,
    /** The sound's data: the network ID of the block making it, or -1 for none. */
    val soundData: Int = -1,
    val releaseIn: Int? = null,
)

/** Whether clicking this block uses it, rather than placing the held item against it. */
val BlockState.usable: Boolean
    get() = name == LEVER || name.endsWith("button") ||
        (name !in OPENED_BY_REDSTONE && (name.endsWith("_door") || name.endsWith("trapdoor") || name.endsWith("fence_gate")))

/**
 * Uses the block at [pos] as a player facing [facing] does by clicking it:
 * a lever flips, a button presses in, and doors, trapdoors and fence gates
 * open or shut, a door's two halves together and a gate away from the
 * player. A button already pressed does nothing. Null for a block that
 * isn't [usable].
 */
fun FlatWorld.use(pos: BlockPos, facing: Face): Used? {
    val state = stateAt(pos)?.takeIf { it.usable } ?: return null
    val name = state.name
    return when {
        name == LEVER -> {
            val on = state.properties[OPEN] == 0.toByte()
            change(pos, state, OPEN to bit(on))
            Used(listOf(pos), if (on) "power.on" else "power.off", blockAt(pos))
        }
        name.endsWith("button") -> {
            if (state.properties[PRESSED] == 1.toByte()) return Used(emptyList())
            change(pos, state, PRESSED to bit(true))
            val releaseIn = if (name.endsWith("stone_button")) STONE_BUTTON_TICKS else WOODEN_BUTTON_TICKS
            Used(listOf(pos), "button.click_on", blockAt(pos), releaseIn)
        }
        name.endsWith("fence_gate") -> {
            val opening = state.properties[OPEN] == 0.toByte()
            // A gate swings away from the player, so one opened from its far side turns round first.
            val turnsRound = opening && state.properties[CARDINAL] == facing.opposite.id
            change(pos, state, OPEN to bit(opening), CARDINAL to if (turnsRound) facing.id else state.properties.getValue(CARDINAL))
            Used(listOf(pos), if (opening) "fence_gate.open" else "fence_gate.close", blockAt(pos))
        }
        else -> {
            val opening = state.properties[OPEN] == 0.toByte()
            change(pos, state, OPEN to bit(opening))
            val other = state.otherHalf(pos)?.takeIf { stateAt(it)?.name == name }
            other?.let { half -> stateAt(half)?.let { change(half, it, OPEN to bit(opening)) } }
            val kind = if (name.endsWith("trapdoor")) "trapdoor" else "door"
            Used(listOfNotNull(pos, other), "$kind.${if (opening) "open" else "close"}", blockAt(pos))
        }
    }
}

/** Lets the button at [pos] pop back out, if it's still pressed in. Returns whether it was. */
fun FlatWorld.releaseButton(pos: BlockPos): Boolean {
    val button = stateAt(pos)?.takeIf { it.name.endsWith("button") && it.properties[PRESSED] == 1.toByte() } ?: return false
    change(pos, button, PRESSED to bit(false))
    return true
}

/** Puts [state] at [pos] with [changes] to its properties, if the game has that state. */
private fun FlatWorld.change(pos: BlockPos, state: BlockState, vararg changes: Pair<String, Any>) {
    blocks.find(state.name, state.properties + changes)?.let { set(pos, it) }
}

/** A bit property's value, which block states keep as a byte. */
private fun bit(on: Boolean): Byte = if (on) 1 else 0
