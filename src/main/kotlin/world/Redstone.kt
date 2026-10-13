package org.koremc.world

import org.koremc.registry.BlockState

/**
 * How far from a block that changed redstone looks for what the change
 * may have powered, or stopped powering: two steps, from a lever to beside
 * the block it's fixed to.
 */
private const val POWER_REACH = 2

/**
 * Whether the block at [pos] is powered: a lever that's on, or a button
 * pressed in, is beside it, or fixed to a block beside it that power
 * passes through, which then powers what's around it too. Levers and
 * buttons are all the power Kore has yet.
 */
fun FlatWorld.isPowered(pos: BlockPos): Boolean = Face.entries.any { face ->
    val beside = pos.side(face)
    val state = stateAt(beside) ?: return@any false
    state.givesPower || (state.conductsPower && Face.entries.any { from -> powersThrough(beside.side(from), beside) })
}

/** Whether the block at [source] is a lever or button that's on, fixed to the block at [block]. */
private fun FlatWorld.powersThrough(source: BlockPos, block: BlockPos): Boolean {
    val state = stateAt(source)?.takeIf { it.givesPower } ?: return false
    return state.heldFrom?.let(source::side) == block
}

/** Whether power passes through this block to what's around it: a whole one, though not glass, leaves or ice. */
internal val BlockState.conductsPower get() = isFullCube && !letsLightThrough

/**
 * Brings what redstone powers in line with the blocks that changed since
 * it last looked: a dispenser or dropper that power has just reached fires
 * a moment later, and a hopper is locked for as long as it's powered.
 * Returns where blocks changed.
 */
internal fun FlatWorld.updatePower(): List<BlockPos> {
    if (powerChanges.isEmpty()) return emptyList()
    val near = powerChanges.flatMapTo(LinkedHashSet()) { it.around(POWER_REACH) }
    powerChanges.clear()
    val changed = mutableListOf<BlockPos>()
    for (pos in near) {
        if (pos.y !in MIN_Y..MAX_Y) continue
        val state = stateAt(pos) ?: continue
        val property = when {
            state.name == HOPPER -> HOPPER_LOCKED
            state.dispenserKind != null -> TRIGGERED
            else -> continue
        }
        val powered = isPowered(pos)
        if ((state.properties[property] == 1.toByte()) == powered) continue
        blocks.find(state.name, state.properties + (property to bit(powered)))?.let { set(pos, it); changed += pos }
        if (powered) dispenserAt(pos)?.fireIn = DISPENSE_DELAY
    }
    return changed
}

/** The blocks no more than [reach] steps from this one, along the axes, this one among them. */
private fun BlockPos.around(reach: Int): List<BlockPos> {
    val around = mutableListOf<BlockPos>()
    for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= reach) around += BlockPos(x + dx, y + dy, z + dz)
    }
    return around
}
