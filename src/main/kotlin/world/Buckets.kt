package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.LiquidReaction

/**
 * Takes a liquid source into an empty bucket, clicked on [face] of the
 * block at [clicked]: one at the clicked block, like the water inside it,
 * or else one in the space beyond the face, which the player looked
 * through, as the client lets them look through liquid at the block
 * behind it. A source block leaves air, and the water inside a block
 * leaves the block. Returns the liquid taken, and where from, or null for
 * none, as flowing liquid can't be.
 */
fun FlatWorld.fillBucket(clicked: BlockPos, face: Face): Pair<Liquid, BlockPos>? {
    for (pos in listOf(clicked, clicked.side(face))) {
        if (pos.y !in MIN_Y..MAX_Y) continue
        val here = liquidHere(pos)?.takeIf { it.depth == 0 } ?: continue
        if (here.inside) setLiquid(pos, null) else set(pos, blocks.stateOf(AIR))
        return here.liquid to pos
    }
    return null
}

/**
 * Empties a bucket of [liquid] against [face] of the block at [clicked],
 * as a source: into the clicked block, if it's water and the block holds
 * water, as a fence does, or takes the place of what a placed block
 * replaces, and otherwise into the space beyond the face, the same way.
 * A block that already has water inside keeps it, as it is, and the water
 * goes nowhere else. What the source takes the place of breaks, into
 * [broken]. Returns where it went, or null if there's no room.
 */
fun FlatWorld.emptyBucket(liquid: Liquid, clicked: BlockPos, face: Face, broken: MutableMap<BlockPos, BrokenBlock>): BlockPos? {
    if (holdsBucket(liquid, clicked)) {
        if (liquidAt(clicked) != null) return null
        setLiquid(clicked, liquidState(Liquid.WATER, 0, inside = true))
        return clicked
    }
    val target = if (stateAt(clicked)?.replaceable == true) clicked else clicked.side(face)
    if (target.y !in MIN_Y..MAX_Y) return null
    if (holdsBucket(liquid, target)) {
        if (liquidAt(target) != null) return null
        setLiquid(target, liquidState(Liquid.WATER, 0, inside = true))
        return target
    }
    val state = stateAt(target) ?: return null
    if (!state.replaceable && reactionTo(state) == LiquidReaction.BLOCKING) return null
    if (state.name != AIR && state.liquid == null) breakAt(target, state, broken)
    set(target, liquidState(liquid, 0))
    return target
}

/** Whether a bucket of [liquid] emptied on the block at [pos] goes inside it: water, into a block that holds water, and isn't one water breaks. */
private fun FlatWorld.holdsBucket(liquid: Liquid, pos: BlockPos): Boolean {
    val state = stateAt(pos) ?: return false
    return liquid == Liquid.WATER && holdsWater(state) && reactionTo(state) != LiquidReaction.POPPED
}
