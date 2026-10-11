package org.koremc.world

import org.koremc.registry.BlockState

internal const val RAIL_DIRECTION = "rail_direction"

// The shapes a rail takes, numbered as `rail_direction` numbers them: flat, sloping up towards a side, or curved.
internal const val RAIL_NORTH_SOUTH = 0
internal const val RAIL_EAST_WEST = 1
private const val RAIL_ASCENDING_EAST = 2
private const val RAIL_ASCENDING_WEST = 3
private const val RAIL_ASCENDING_NORTH = 4
private const val RAIL_ASCENDING_SOUTH = 5
private const val RAIL_SOUTH_EAST = 6
private const val RAIL_SOUTH_WEST = 7
private const val RAIL_NORTH_WEST = 8
private const val RAIL_NORTH_EAST = 9

private val BlockState.isRail get() = RAIL_DIRECTION in properties

/** Whether a rail can't curve: powered, detector and activator rails only run straight or slope. */
private val BlockState.straightOnly get() = name != "minecraft:rail"

/**
 * One rail and the places it reaches to join others, worked out as Java
 * Edition's rail logic does. A rail reaches out of both its ends, up a
 * block at the end it slopes up to.
 */
private class Rail(val world: FlatWorld, val pos: BlockPos, var state: BlockState) {
    val connections = mutableListOf<BlockPos>()

    init {
        connect(shape)
    }

    val shape get() = state.properties[RAIL_DIRECTION] as Int

    fun connect(shape: Int) {
        connections.clear()
        val north = pos.side(Face.NORTH)
        val south = pos.side(Face.SOUTH)
        val west = pos.side(Face.WEST)
        val east = pos.side(Face.EAST)
        connections += when (shape) {
            RAIL_NORTH_SOUTH -> listOf(north, south)
            RAIL_EAST_WEST -> listOf(west, east)
            RAIL_ASCENDING_EAST -> listOf(west, east.side(Face.UP))
            RAIL_ASCENDING_WEST -> listOf(west.side(Face.UP), east)
            RAIL_ASCENDING_NORTH -> listOf(north.side(Face.UP), south)
            RAIL_ASCENDING_SOUTH -> listOf(north, south.side(Face.UP))
            RAIL_SOUTH_EAST -> listOf(east, south)
            RAIL_SOUTH_WEST -> listOf(west, south)
            RAIL_NORTH_WEST -> listOf(west, north)
            else -> listOf(east, north)
        }
    }

    /** Drops the places it reaches that hold no rail reaching back. */
    fun dropLooseEnds() {
        val iterator = connections.listIterator()
        while (iterator.hasNext()) {
            val other = railNear(iterator.next())
            if (other != null && other.reaches(this)) iterator.set(other.pos) else iterator.remove()
        }
    }

    fun reaches(other: Rail) = connections.any { it.x == other.pos.x && it.z == other.pos.z }

    fun reachesAt(pos: BlockPos) = connections.any { it.x == pos.x && it.z == pos.z }

    /** Whether it can join [other]: it reaches it already, or has an end free. */
    fun canJoin(other: Rail) = reaches(other) || connections.size != 2

    /** The rail at [pos], or a block above or below it, if there's one. */
    fun railNear(pos: BlockPos): Rail? =
        listOf(pos, pos.side(Face.UP), pos.side(Face.DOWN)).firstNotNullOfOrNull { at -> world.stateAt(at)?.takeIf { it.isRail }?.let { Rail(world, at, it) } }

    private fun railAt(pos: BlockPos) = world.stateAt(pos)?.isRail == true

    private fun neighbourJoins(pos: BlockPos): Boolean {
        val other = railNear(pos) ?: return false
        other.dropLooseEnds()
        return other.canJoin(this)
    }

    /** A flat shape sloping up towards a rail a block up beside its end, if there is one. */
    private fun sloped(flat: Int): Int {
        val north = pos.side(Face.NORTH)
        val south = pos.side(Face.SOUTH)
        val west = pos.side(Face.WEST)
        val east = pos.side(Face.EAST)
        var shape = flat
        if (flat == RAIL_NORTH_SOUTH) {
            if (railAt(north.side(Face.UP))) shape = RAIL_ASCENDING_NORTH
            if (railAt(south.side(Face.UP))) shape = RAIL_ASCENDING_SOUTH
        }
        if (flat == RAIL_EAST_WEST) {
            if (railAt(east.side(Face.UP))) shape = RAIL_ASCENDING_EAST
            if (railAt(west.side(Face.UP))) shape = RAIL_ASCENDING_WEST
        }
        return shape
    }

    /** Turns to join the rail [other], which reaches it, and returns where it changed. */
    fun join(other: Rail): BlockPos {
        connections += other.pos
        val north = reachesAt(pos.side(Face.NORTH))
        val south = reachesAt(pos.side(Face.SOUTH))
        val west = reachesAt(pos.side(Face.WEST))
        val east = reachesAt(pos.side(Face.EAST))
        var shape: Int? = null
        if (north || south) shape = RAIL_NORTH_SOUTH
        if (west || east) shape = RAIL_EAST_WEST
        if (!state.straightOnly) {
            if (south && east && !north && !west) shape = RAIL_SOUTH_EAST
            if (south && west && !north && !east) shape = RAIL_SOUTH_WEST
            if (north && west && !south && !east) shape = RAIL_NORTH_WEST
            if (north && east && !south && !west) shape = RAIL_NORTH_EAST
        }
        setShape(shape?.let(::sloped) ?: RAIL_NORTH_SOUTH)
        return pos
    }

    /**
     * Takes the shape that joins the rails around it, starting from
     * [shape], which a rail placed by a player has from the way they face,
     * and makes those rails turn to join it. Returns where rails changed.
     */
    fun settle(shape: Int): List<BlockPos> {
        val north = neighbourJoins(pos.side(Face.NORTH))
        val south = neighbourJoins(pos.side(Face.SOUTH))
        val west = neighbourJoins(pos.side(Face.WEST))
        val east = neighbourJoins(pos.side(Face.EAST))
        val alongZ = north || south
        val alongX = west || east
        var placed: Int? = null
        if (alongZ && !alongX) placed = RAIL_NORTH_SOUTH
        if (alongX && !alongZ) placed = RAIL_EAST_WEST
        val southEast = south && east
        val southWest = south && west
        val northEast = north && east
        val northWest = north && west
        if (!state.straightOnly) {
            if (southEast && !north && !west) placed = RAIL_SOUTH_EAST
            if (southWest && !north && !east) placed = RAIL_SOUTH_WEST
            if (northWest && !south && !east) placed = RAIL_NORTH_WEST
            if (northEast && !south && !west) placed = RAIL_NORTH_EAST
        }
        if (placed == null) {
            placed = when {
                alongZ && alongX -> shape
                alongZ -> RAIL_NORTH_SOUTH
                alongX -> RAIL_EAST_WEST
                else -> null
            }
            if (!state.straightOnly) {
                if (northWest) placed = RAIL_NORTH_WEST
                if (northEast) placed = RAIL_NORTH_EAST
                if (southWest) placed = RAIL_SOUTH_WEST
                if (southEast) placed = RAIL_SOUTH_EAST
            }
        }
        val final = placed?.let(::sloped) ?: shape
        connect(final)
        setShape(final)

        val changed = mutableListOf<BlockPos>()
        for (end in connections.toList()) {
            val other = railNear(end) ?: continue
            other.dropLooseEnds()
            if (other.canJoin(this)) changed += other.join(this)
        }
        return changed
    }

    private fun setShape(shape: Int) {
        state = world.blocks.find(state.name, state.properties + (RAIL_DIRECTION to shape)) ?: state
        world[pos] = state
    }
}

/**
 * Settles the rail just placed at [pos] by a player facing [facing]: it
 * joins the rails around it, curving or sloping to meet them, and they
 * turn to join it. Returns where other rails changed.
 */
internal fun FlatWorld.railPlaced(pos: BlockPos, facing: Face): List<BlockPos> {
    val state = stateAt(pos)?.takeIf { it.isRail } ?: return emptyList()
    val shape = if (facing.axis == "x") RAIL_EAST_WEST else RAIL_NORTH_SOUTH
    return Rail(this, pos, state).settle(shape).distinct() - pos
}
