package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState

internal const val NETHER_PORTAL = "minecraft:portal"
private const val OBSIDIAN = "minecraft:obsidian"
private const val PORTAL_AXIS = "portal_axis"

/** The most blocks a portal's inside runs, across and up, and the fewest across, and up. */
private const val MOST = 21
private const val FEWEST_ACROSS = 2
private const val FEWEST_UP = 3

/** What a portal opens in: air, fire, or portal already. */
private val BlockState?.emptyForPortal get() = this != null && (name == AIR || isFire || name == NETHER_PORTAL)

private val BlockState?.isFrame get() = this?.name == OBSIDIAN

/** The way the portal [state] runs across: east for along the x axis, and south for the z. */
private fun acrossOf(state: BlockState) = if (state.properties[PORTAL_AXIS] == "z") Face.SOUTH else Face.EAST

/** The inside of a nether portal frame: its bottom corner, [corner], the way it runs from there, [across], and how many blocks across and up. */
private class PortalShape(val corner: BlockPos, val across: Face, val width: Int, val height: Int)

/**
 * Opens a nether portal in the empty obsidian frame around [pos], as fire
 * starting there does: its whole inside fills with portal. A frame is a
 * rectangle of obsidian around an inside two to 21 blocks across and three
 * to 21 up, of nothing but air or fire, its corners left out or not. One
 * running along the x axis is looked for before one along the z. Returns
 * where blocks changed, or null if [pos] isn't inside an empty frame.
 */
internal fun FlatWorld.openNetherPortal(pos: BlockPos): List<BlockPos>? {
    val shape = listOf(Face.EAST, Face.SOUTH).firstNotNullOfOrNull { emptyFrameAround(pos, it) } ?: return null
    val portal = blocks.find(NETHER_PORTAL, mapOf(PORTAL_AXIS to if (shape.across == Face.EAST) "x" else "z")) ?: return null
    val inside = (0 until shape.height).flatMap { up -> (0 until shape.width).map { step -> shape.corner.side(shape.across, step).side(Face.UP, up) } }
    inside.forEach { set(it, portal) }
    return inside
}

/** The inside of the empty frame around [pos] running [across], or null if there isn't one, as the game finds it. */
private fun FlatWorld.emptyFrameAround(pos: BlockPos, across: Face): PortalShape? {
    val back = across.opposite
    // Down to the frame's bottom, then back to its side.
    var at = pos
    val lowest = maxOf(MIN_Y, pos.y - MOST)
    while (at.y > lowest && stateAt(at.side(Face.DOWN)).emptyForPortal) at = at.side(Face.DOWN)
    val toSide = toFrameSide(at, back) - 1
    if (toSide < 0) return null
    val corner = at.side(back, toSide)
    val width = toFrameSide(corner, across).takeIf { it in FEWEST_ACROSS..MOST } ?: return null
    // Up a row at a time, while there's frame either side and nothing inside.
    var height = 0
    var portals = 0
    rows@ while (height < MOST) {
        val row = corner.side(Face.UP, height)
        if (!stateAt(row.side(back)).isFrame || !stateAt(row.side(across, width)).isFrame) break
        for (step in 0 until width) {
            val inside = stateAt(row.side(across, step))
            if (!inside.emptyForPortal) break@rows
            if (inside?.name == NETHER_PORTAL) portals++
        }
        height++
    }
    if (height < FEWEST_UP || portals > 0 || (0 until width).any { !stateAt(corner.side(Face.UP, height).side(across, it)).isFrame }) return null
    return PortalShape(corner, across, width, height)
}

/**
 * How many blocks from [from] toward [toward] the side of a frame is,
 * along the top of its bottom, or 0 if there's anything else in the way,
 * or the bottom runs out first, within reach.
 */
private fun FlatWorld.toFrameSide(from: BlockPos, toward: Face): Int {
    for (step in 0..MOST) {
        val at = from.side(toward, step)
        val state = stateAt(at)
        if (!state.emptyForPortal) return if (state.isFrame) step else 0
        if (!stateAt(at.side(Face.DOWN)).isFrame) return 0
    }
    return 0
}

/** Whether the portal block at [pos], [state], is still held in its frame: portal or obsidian above and below it, and either side along the way it runs. */
internal fun FlatWorld.portalHeld(pos: BlockPos, state: BlockState): Boolean {
    val across = acrossOf(state)
    return listOf(Face.UP, Face.DOWN, across, across.opposite).all { side -> stateAt(pos.side(side)).let { it?.name == NETHER_PORTAL || it.isFrame } }
}

/**
 * Closes the nether portal the block at [pos] is part of, all of it at
 * once, as the game does once its frame breaks, each block breaking. Notes
 * what broke in [broken].
 */
internal fun FlatWorld.closeNetherPortal(pos: BlockPos, broken: MutableMap<BlockPos, BrokenBlock>) {
    val across = acrossOf(stateAt(pos) ?: return)
    val next = ArrayDeque(listOf(pos))
    while (next.isNotEmpty()) {
        val at = next.removeFirst()
        val state = stateAt(at)?.takeIf { it.name == NETHER_PORTAL && at !in broken } ?: continue
        breakAt(at, state, broken)
        listOf(Face.UP, Face.DOWN, across, across.opposite).mapTo(next, at::side)
    }
}
