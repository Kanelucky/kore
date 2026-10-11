package org.koremc.world

import org.koremc.registry.BlockState

private const val WALL_POST = "wall_post_bit"
private const val IN_WALL = "in_wall_bit"
private const val ATTACHED = "attached_bit"
private const val THICKNESS = "dripstone_thickness"

internal const val TRIPWIRE = "minecraft:trip_wire"
internal const val TRIPWIRE_HOOK = "minecraft:tripwire_hook"

private fun connection(side: Face) = "minecraft:connection_${side.id}"

private fun wallConnection(side: Face) = "wall_connection_type_${side.id}"

private val BlockState.isFence get() = name.endsWith("_fence")
private val BlockState.isPane get() = name.endsWith("glass_pane") || name.endsWith("_bars")
private val BlockState.isWall get() = WALL_POST in properties
private val BlockState.isGate get() = name.endsWith("fence_gate")

/** Which way stairs climb, or null for a block that isn't stairs. */
private val BlockState.climb get() = (properties["weirdo_direction"] as? Int)?.let(STAIRS_CLIMB::getOrNull)

/**
 * Whether fences, panes and walls keep apart from this block, whole though
 * it is: leaves, barriers, pumpkins, melons and shulker boxes. Bedrock
 * follows Java Edition here.
 */
private val BlockState.keptApart get() = "leaves" in name || name.endsWith("shulker_box") || name in KEPT_APART

/** Which way a fence gate faces, and so the axis it swings across. Its span runs the other way. */
private val BlockState.gateFacing get() = Face.entries.find { it.id == properties[CARDINAL] }

/**
 * [state] at [pos] shaped by the blocks around it, for the blocks that
 * join their neighbours. Fences, panes and walls reach out to the blocks
 * they join. A wall's sides stand tall under a block that covers them,
 * and it raises a post where it ends or turns. A fence gate drops when
 * set in a wall. Stairs turn a corner against stairs set across them.
 * Tripwire joins the tripwire and hooks beside it, and tripwire hooks
 * strung to one another, with the tripwire between, are attached.
 * Pointed dripstone thickens from its tip back, and scaffolding knows how
 * far it is from what holds it up. Any other block is as it is.
 */
fun FlatWorld.joined(pos: BlockPos, state: BlockState): BlockState {
    val changes = when {
        state.name == SCAFFOLDING -> return scaffoldingShaped(pos, state)
        state.name == TRIPWIRE -> AROUND.associate { side -> connection(side) to bit(tripwireJoins(side, stateAt(pos.side(side)))) } +
            (ATTACHED to bit(tripwireStrung(pos)))
        state.name == TRIPWIRE_HOOK -> mapOf(ATTACHED to bit(hookStrung(pos, state)))
        THICKNESS in state.properties -> mapOf(THICKNESS to dripstoneThickness(pos, state))
        state.isWall -> wallShape(pos, state)
        STAIRS_CORNER in state.properties -> mapOf(STAIRS_CORNER to stairsCorner(pos, state))
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
 * Each joins a whole face, unless it's [keptApart], and fences and walls
 * join a fence gate set across them. Wooden fences join one another, as
 * nether brick fences do. Panes, bars and walls join one another.
 */
private fun joins(block: BlockState, side: Face, neighbour: BlockState?): Boolean = when {
    neighbour == null || neighbour.keptApart -> false
    neighbour.hasFullFace(side.opposite) -> true
    neighbour.isGate -> !block.isPane && neighbour.gateFacing?.axis != side.axis
    block.isFence -> neighbour.isFence && (neighbour.name == NETHER_BRICK_FENCE) == (block.name == NETHER_BRICK_FENCE)
    else -> neighbour.isPane || neighbour.isWall
}

private const val NETHER_BRICK_FENCE = "minecraft:nether_brick_fence"

/** Whole blocks that nothing joins, besides leaves and shulker boxes, which come in many kinds. */
private val KEPT_APART = setOf("minecraft:barrier", "minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:lit_pumpkin", "minecraft:melon_block")

/**
 * The corner [stairs] at [pos] turn, as Java Edition works it out, from
 * the stairs around them of the same half, upright or upside down. Stairs
 * whose tall back meets stairs set across them round an outer corner, and
 * stairs whose low step meets them fill an inner one. Neither happens
 * where it would break a straight run of stairs beside them. A corner is
 * left or right as the stairs climb.
 */
private fun FlatWorld.stairsCorner(pos: BlockPos, stairs: BlockState): String {
    val climb = stairs.climb ?: return "none"
    val upsideDown = stairs.properties["upside_down_bit"]
    fun climbOf(side: Face) = stateAt(pos.side(side))?.takeIf { it.properties["upside_down_bit"] == upsideDown }?.climb
    // Stairs climbing alongside these, in a run a corner would break.
    fun inRun(side: Face) = climbOf(side) == climb

    climbOf(climb)?.takeIf { it.axis != climb.axis && !inRun(it.opposite) }?.let { across ->
        return if (across == climb.counterClockwise) "outer_left" else "outer_right"
    }
    climbOf(climb.opposite)?.takeIf { it.axis != climb.axis && !inRun(it) }?.let { across ->
        return if (across == climb.counterClockwise) "inner_left" else "inner_right"
    }
    return "none"
}

/** Whether tripwire joins [neighbour] on its [side]: more tripwire, or a hook facing it. */
private fun tripwireJoins(side: Face, neighbour: BlockState?): Boolean = when (neighbour?.name) {
    TRIPWIRE -> true
    TRIPWIRE_HOOK -> neighbour.hookFacing == side.opposite
    else -> false
}

/** The way a tripwire hook faces, out of the block it's fixed to, along the tripwire it can be strung to. */
private val BlockState.hookFacing get() = (properties["direction"] as? Int)?.let(Face::ofLegacyIndex)

/**
 * Whether the tripwire hook at [pos], [hook], is strung to another: one
 * facing it, more than a block and at most [TRIPWIRE_REACH] away, with
 * tripwire all the way between.
 */
private fun FlatWorld.hookStrung(pos: BlockPos, hook: BlockState): Boolean {
    val facing = hook.hookFacing ?: return false
    for (distance in 1..TRIPWIRE_REACH) {
        val along = stateAt(BlockPos(pos.x + facing.dx * distance, pos.y, pos.z + facing.dz * distance)) ?: return false
        when (along.name) {
            TRIPWIRE -> continue
            TRIPWIRE_HOOK -> return along.hookFacing == facing.opposite && distance > 1
            else -> return false
        }
    }
    return false
}

/** Whether the tripwire at [pos] lies between two hooks strung to one another, along either axis. */
private fun FlatWorld.tripwireStrung(pos: BlockPos): Boolean = listOf(Face.EAST, Face.SOUTH).any { side ->
    val ends = listOf(side, side.opposite).map { direction ->
        var at = pos.side(direction)
        var distance = 1
        while (stateAt(at)?.name == TRIPWIRE && distance <= TRIPWIRE_REACH) {
            at = at.side(direction)
            distance++
        }
        at to stateAt(at)
    }
    ends.all { (at, end) -> end?.name == TRIPWIRE_HOOK && hookStrung(at, end) }
}

/**
 * How thick pointed dripstone is, worked out as Java Edition does from
 * the dripstone beyond its tip and behind it: the tip at the end of a
 * run, then the frustum behind it, a middle, and the base the run grows
 * from. A tip that meets another coming the other way merges with it.
 */
private fun FlatWorld.dripstoneThickness(pos: BlockPos, dripstone: BlockState): String {
    val hanging = dripstone.properties[HANGING]
    val pointing = if (hanging == 1.toByte()) Face.DOWN else Face.UP
    fun isDripstone(at: BlockPos, pointingSame: Boolean) = stateAt(at)?.takeIf { it.name == dripstone.name && (it.properties[HANGING] == hanging) == pointingSame }
    val ahead = pos.side(pointing)
    isDripstone(ahead, pointingSame = false)?.let { return if (dripstone.properties[THICKNESS] == "merge" || it.properties[THICKNESS] == "merge") "merge" else "tip" }
    val next = isDripstone(ahead, pointingSame = true) ?: return "tip"
    return when (next.properties[THICKNESS]) {
        "tip", "merge" -> "frustum"
        else -> if (isDripstone(pos.side(pointing.opposite), pointingSame = true) != null) "middle" else "base"
    }
}

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
