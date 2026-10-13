package org.koremc.world

import org.koremc.registry.BlockState

/** How long coral out of water takes to die, in ticks, once a block beside it changes. It never dies by itself. */
private const val CORAL_DEATH_TICKS = 10

/** Whether this is living coral: a coral block, a coral plant or a coral fan, on a floor or a wall. */
internal val BlockState.isLivingCoral get() = "coral" in name && !name.startsWith("minecraft:dead_")

/** Looks at the coral at [pos] again in a little while, to see whether it's out of water and dies. */
internal fun FlatWorld.scheduleCoralCheck(pos: BlockPos) = schedule(pos, CORAL_DEATH_TICKS, BlockUpdate.CORAL)

/**
 * Kills the coral at [pos], [state], if there's no water in or beside it:
 * it becomes its dead kind, as it is otherwise, with the sound of the dead
 * kind being placed. Returns that sound, or null if it lives on.
 */
internal fun FlatWorld.coralDies(pos: BlockPos, state: BlockState): BlockSound? {
    if (!state.isLivingCoral || isWet(pos) || Face.entries.any { isWet(pos.side(it)) }) return null
    val dead = blocks.find(state.name.replace("minecraft:", "minecraft:dead_"), state.properties) ?: return null
    set(pos, dead)
    return BlockSound(pos, "place", dead.networkId)
}
