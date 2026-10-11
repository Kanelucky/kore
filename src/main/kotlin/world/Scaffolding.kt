package org.koremc.world

import org.koremc.registry.BlockState

internal const val SCAFFOLDING = "minecraft:scaffolding"

/** How many blocks scaffolding reaches out from what holds it up, plus one: at this distance, it can't stay. */
internal const val SCAFFOLDING_MAX_DISTANCE = 7

private const val STABILITY = "stability"

/**
 * How far the scaffolding at [pos] is from what holds it up, in blocks
 * out sideways: 0 on a block with a whole top, the same as the scaffolding
 * it stands on, or one more than the nearest scaffolding beside it, up to
 * [SCAFFOLDING_MAX_DISTANCE] for none in reach.
 */
internal fun FlatWorld.scaffoldingDistance(pos: BlockPos): Int {
    val below = stateAt(pos.side(Face.DOWN))
    var distance = SCAFFOLDING_MAX_DISTANCE
    when {
        below?.name == SCAFFOLDING -> distance = below.stability
        below?.hasFullFace(Face.UP) == true -> return 0
    }
    for (side in AROUND) {
        val beside = stateAt(pos.side(side))?.takeIf { it.name == SCAFFOLDING } ?: continue
        distance = minOf(distance, beside.stability + 1)
    }
    return distance
}

private val BlockState.stability get() = properties[STABILITY] as? Int ?: SCAFFOLDING_MAX_DISTANCE

/** [scaffolding] at [pos], knowing how far it is from what holds it up, or null if that's too far. */
internal fun FlatWorld.scaffoldingAt(pos: BlockPos, scaffolding: BlockState): BlockState? {
    val distance = scaffoldingDistance(pos)
    if (distance >= SCAFFOLDING_MAX_DISTANCE) return null
    return blocks.find(SCAFFOLDING, scaffolding.properties + (STABILITY to distance))
}

/** Scaffolding at [pos] with its distance brought up to date, for [joined]. */
internal fun FlatWorld.scaffoldingShaped(pos: BlockPos, scaffolding: BlockState): BlockState {
    val distance = minOf(scaffoldingDistance(pos), SCAFFOLDING_MAX_DISTANCE)
    return blocks.find(SCAFFOLDING, scaffolding.properties + (STABILITY to distance)) ?: scaffolding
}

/**
 * Places [scaffolding] by clicking [face] of the block at [clicked], when
 * that's scaffolding, or the scaffolding beyond it is, as when a player
 * standing in scaffolding clicks the ground under it. Clicking a top
 * builds the column up, into the first space above it, and clicking a side
 * reaches out that way, into the first space beyond the scaffolding there,
 * at most [SCAFFOLDING_MAX_DISTANCE] blocks out. Clicking an underside
 * places beneath it, as with any block. The new scaffolding must be in
 * reach of what holds it up. Returns where it went, or nothing.
 */
internal fun FlatWorld.placeScaffolding(scaffolding: BlockState, clicked: BlockPos, face: Face, placer: Placer): List<BlockPos> {
    var target = clicked.side(face)
    if (face != Face.DOWN) {
        var steps = 0
        while (stateAt(target)?.name == SCAFFOLDING) {
            if (face.horizontal && ++steps >= SCAFFOLDING_MAX_DISTANCE) return emptyList()
            target = target.side(face)
        }
    }
    if (target.y !in MIN_Y..MAX_Y || stateAt(target)?.replaceable != true) return emptyList()
    val state = scaffoldingAt(target, blocks.placedState(scaffolding, target, face, 0f, placer)) ?: return emptyList()
    set(target, state)
    return listOf(target)
}
