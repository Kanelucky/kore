package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState

private const val PORTAL_FRAME = "minecraft:end_portal_frame"
private const val PORTAL = "minecraft:end_portal"
private const val EYE = "end_portal_eye_bit"
internal const val ENDER_EYE = "minecraft:ender_eye"

/** How far from the middle of a portal its frames are. */
private const val RING = 2

/** Whether this is an end portal frame with an eye of ender in it. */
private val BlockState.hasEye get() = name == PORTAL_FRAME && properties[EYE] == 1.toByte()

/**
 * Puts an eye of ender in the end portal frame at [pos], [state], if it
 * hasn't one, using the eye up. Once every frame of a ring of twelve,
 * three on each side of a square five blocks across, faces in and has an
 * eye, the portal opens in the middle, with its sound. Null if the frame
 * has its eye already, as an eye never comes out.
 */
fun FlatWorld.fillPortalFrame(pos: BlockPos, state: BlockState): ItemUsedOn? {
    if (state.name != PORTAL_FRAME || state.hasEye) return null
    val filled = blocks.find(PORTAL_FRAME, state.properties + (EYE to bit(true))) ?: return null
    set(pos, filled)
    val middle = portalMiddle(pos) ?: return ItemUsedOn(listOf(pos), BlockSound(pos, "block.end_portal_frame.fill", -1), HeldAfter.USED_UP)
    val portal = blocks.stateOf(PORTAL)
    val opened = (-1..1).flatMap { dx -> (-1..1).map { dz -> BlockPos(middle.x + dx, middle.y, middle.z + dz) } }
    opened.forEach { set(it, portal) }
    return ItemUsedOn(listOf(pos) + opened, BlockSound(middle, "block.end_portal.spawn", -1), HeldAfter.USED_UP)
}

/**
 * The middle of a complete ring of frames, all facing in, with eyes, that
 * the frame at [pos] is part of, or null if it isn't part of one.
 */
private fun FlatWorld.portalMiddle(pos: BlockPos): BlockPos? {
    val facing = Face.entries.find { it.horizontal && it.id == stateAt(pos)?.properties?.get(CARDINAL) } ?: return null
    // It faces the middle, two blocks on, and a block either side of straight ahead.
    val across = Face.entries.first { it.horizontal && it.dx == facing.dz && it.dz == facing.dx }
    return (-1..1).map { step -> BlockPos(pos.x + facing.dx * RING + across.dx * step, pos.y, pos.z + facing.dz * RING + across.dz * step) }
        .firstOrNull { middle -> ringAround(middle) }
}

/**
 * Closes the end portal beside the frame [frame] that just broke at [pos],
 * as the game does: every block of the portal goes, leaving air. Returns
 * where they were, or nothing if [frame] wasn't a portal's frame.
 */
internal fun FlatWorld.closePortalBy(pos: BlockPos, frame: BlockState): List<BlockPos> {
    if (frame.name != PORTAL_FRAME) return emptyList()
    val closed = LinkedHashSet<BlockPos>()
    val next = ArrayDeque(AROUND.map(pos::side))
    while (next.isNotEmpty()) {
        val at = next.removeFirst()
        if (at in closed || stateAt(at)?.name != PORTAL) continue
        closed += at
        AROUND.mapTo(next, at::side)
    }
    val air = blocks.stateOf(AIR)
    closed.forEach { set(it, air) }
    return closed.toList()
}

/** Whether there's a frame with an eye, facing [middle], on each of the twelve spots of the ring around it. */
private fun FlatWorld.ringAround(middle: BlockPos): Boolean = AROUND.all { side ->
    val across = AROUND.first { it.dx == side.dz && it.dz == side.dx }
    (-1..1).all { step ->
        val at = BlockPos(middle.x + side.dx * RING + across.dx * step, middle.y, middle.z + side.dz * RING + across.dz * step)
        val frame = stateAt(at)
        frame != null && frame.hasEye && frame.properties[CARDINAL] == side.opposite.id
    }
}
