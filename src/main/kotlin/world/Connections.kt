package org.koremc.world

import org.koremc.registry.BlockState

private const val WALL_POST = "wall_post_bit"
private const val IN_WALL = "in_wall_bit"
/** The four sides a block can join others on. */
private val AROUND = listOf(Face.NORTH, Face.EAST, Face.SOUTH, Face.WEST)

private fun connection(side: Face) = "minecraft:connection_${side.id}"

private fun wallConnection(side: Face) = "wall_connection_type_${side.id}"

private val BlockState.isFence get() = name.endsWith("_fence")
private val BlockState.isPane get() = name.endsWith("glass_pane") || name.endsWith("_bars")
private val BlockState.isWall get() = WALL_POST in properties
private val BlockState.isGate get() = name.endsWith("fence_gate")

/** Which way a fence gate faces, and so the axis it swings across. Its span runs the other way. */
private val BlockState.gateFacing get() = Face.entries.find { it.id == properties[CARDINAL] }

/**
 * [state] at [pos] shaped by the blocks around it, for the blocks that
 * join their neighbours. Fences, panes and walls reach out to the blocks
 * they join. A wall's sides stand tall under a block that covers them,
 * and it raises a post where it ends or turns. A fence gate drops when
 * set in a wall. Any other block is as it is.
 */
fun FlatWorld.joined(pos: BlockPos, state: BlockState): BlockState {
    val changes = when {
        state.isWall -> wallShape(pos, state)
        state.isFence || state.isPane -> AROUND.associate { side -> connection(side) to bit(joins(state, side, stateAt(pos.side(side)))) }
        state.isGate -> {
            val span = state.gateFacing?.clockwise ?: return state
            val inWall = listOf(span, span.opposite).any { stateAt(pos.side(it))?.isWall == true }
            mapOf(IN_WALL to bit(inWall))
        }
        else -> return state
    }
    return blocks.find(state.name, state.properties + changes) ?: state
}

/**
 * Shapes again the blocks around those at [changed] that join their
 * neighbours, since a change can join or part them: those beside each,
 * and the one below, which might be a wall it stands on. Each that changes
 * can change its own neighbours in turn. Returns where blocks changed.
 */
fun FlatWorld.updateAround(changed: Collection<BlockPos>): List<BlockPos> {
    val updated = LinkedHashSet<BlockPos>()
    val toCheck = ArrayDeque(changed)
    while (toCheck.isNotEmpty()) {
        val at = toCheck.removeFirst()
        for (next in AROUND.map(at::side) + at.side(Face.DOWN)) {
            val state = stateAt(next) ?: continue
            val shaped = joined(next, state)
            if (shaped == state) continue
            set(next, shaped)
            updated += next
            toCheck += next
        }
    }
    return updated.toList()
}

/**
 * Whether [block], a fence, pane or wall, joins [neighbour] on its [side].
 * Each joins a whole face, and fences and walls join a fence gate set
 * across them. Wooden fences join one another, as nether brick fences do.
 * Panes, bars and walls join one another.
 */
private fun joins(block: BlockState, side: Face, neighbour: BlockState?): Boolean = when {
    neighbour == null -> false
    neighbour.hasFullFace(side.opposite) -> true
    neighbour.isGate -> !block.isPane && neighbour.gateFacing?.axis != side.axis
    block.isFence -> neighbour.isFence && (neighbour.name == NETHER_BRICK_FENCE) == (block.name == NETHER_BRICK_FENCE)
    else -> neighbour.isPane || neighbour.isWall
}

private const val NETHER_BRICK_FENCE = "minecraft:nether_brick_fence"

/**
 * A wall's sides and post. A side it joins on is tall when the block
 * above covers it: a whole cube, or a wall joining on that side too, and
 * short otherwise. The post rises where the wall stands alone, ends or
 * turns a corner, or where a wall post or a torch stands on it. Along a
 * straight run, it rises only for a torch, and not at all where the run is
 * tall at both ends.
 */
private fun FlatWorld.wallShape(pos: BlockPos, wall: BlockState): Map<String, Any> {
    val above = stateAt(pos.side(Face.UP))
    val sides = AROUND.associateWith { side ->
        when {
            !joins(wall, side, stateAt(pos.side(side))) -> "none"
            above?.isFullCube == true || (above?.isWall == true && above.properties[wallConnection(side)] != "none") -> "tall"
            else -> "short"
        }
    }
    val joined = { side: Face -> sides.getValue(side) != "none" }
    val post = when {
        above?.isWall == true && above.properties[WALL_POST] == 1.toByte() -> true
        // Alone, at an end, or at a corner.
        AROUND.none(joined) || joined(Face.NORTH) != joined(Face.SOUTH) || joined(Face.EAST) != joined(Face.WEST) -> true
        sides[Face.NORTH] == "tall" && sides[Face.SOUTH] == "tall" || sides[Face.EAST] == "tall" && sides[Face.WEST] == "tall" -> false
        else -> above?.properties?.get("torch_facing_direction") == "top"
    }
    return sides.mapKeys { (side, _) -> wallConnection(side) } + (WALL_POST to bit(post))
}
