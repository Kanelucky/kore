package org.koremc.world

import org.koremc.registry.BlockState

internal const val BIG_DRIPLEAF = "minecraft:big_dripleaf"
private const val HEAD = "big_dripleaf_head"
private const val TILT = "big_dripleaf_tilt"

/**
 * How long a big dripleaf takes over each step of tipping over, in ticks:
 * it wobbles as soon as something stands on it, tips a second later, tips
 * right over, dropping what stood on it, half a second after that, and
 * rights itself after five seconds more.
 */
private const val TICKS_UNTIL_TILT = 20
private const val TICKS_UNTIL_FULL_TILT = 10
private const val TICKS_UNTIL_UPRIGHT = 100

/** How far up its block a big dripleaf's leaf starts: what stands above that stands on the leaf. */
private const val LEAF_BOTTOM = 11.0 / 16

private val BlockState.isDripleafLeaf get() = name == BIG_DRIPLEAF && properties[HEAD] == 1.toByte()

/**
 * The heights within its block that a big dripleaf's leaf holds things up
 * at, as it tips: lower once it's tipped, and nothing once it's tipped
 * right over. Null for the stem, or a block that isn't a big dripleaf.
 */
internal fun BlockState.leafHeights(): ClosedFloatingPointRange<Double>? {
    if (!isDripleafLeaf) return null
    return when (properties[TILT]) {
        "full_tilt" -> null
        "partial_tilt" -> LEAF_BOTTOM..13.0 / 16
        else -> LEAF_BOTTOM..15.0 / 16
    }
}

/** A big dripleaf placed at [pos] on another faces the way the plant does. */
internal fun FlatWorld.dripleafPlaced(pos: BlockPos, dripleaf: BlockState): BlockState {
    val below = stateAt(pos.side(Face.DOWN))?.takeIf { it.name == BIG_DRIPLEAF } ?: return dripleaf
    return blocks.find(BIG_DRIPLEAF, dripleaf.properties + (CARDINAL to below.properties.getValue(CARDINAL))) ?: dripleaf
}

/**
 * A big dripleaf at [pos] shaped by what's above it: the leaf at the top of
 * the plant, or stem below more of it, which stands straight.
 */
internal fun FlatWorld.dripleafShaped(pos: BlockPos, dripleaf: BlockState): BlockState {
    val leaf = stateAt(pos.side(Face.UP))?.name != BIG_DRIPLEAF
    if (leaf == (dripleaf.properties[HEAD] == 1.toByte())) return dripleaf
    val changes = mapOf(HEAD to bit(leaf), TILT to if (leaf) dripleaf.properties.getValue(TILT) else "none")
    return blocks.find(BIG_DRIPLEAF, dripleaf.properties + changes) ?: dripleaf
}

/**
 * Starts the big dripleaves that players or items stand on, with their
 * feet at [feet], tipping over: each wobbles, then tips as [tiltOn] goes
 * on. Only something above the bottom of the leaf stands on it. Returns
 * where leaves changed.
 */
fun FlatWorld.stepOn(feet: Collection<Vec3>): List<BlockPos> = feet.mapNotNull { at ->
    val pos = at.toBlockPos()
    val leaf = stateAt(pos)?.takeIf { it.isDripleafLeaf && it.properties[TILT] == "none" } ?: return@mapNotNull null
    if (at.y - pos.y <= LEAF_BOTTOM) return@mapNotNull null
    set(pos, blocks.find(BIG_DRIPLEAF, leaf.properties + (TILT to "unstable")) ?: return@mapNotNull null)
    schedule(pos, TICKS_UNTIL_TILT, BlockUpdate.TILT)
    pos
}.distinct()

/**
 * The next step of the big dripleaf at [pos], [state], tipping over: from
 * wobbling to tipped, to tipped right over, and back upright, each with
 * the sound of the leaf going down or coming up. Returns that sound, or
 * null for a leaf that isn't tipping.
 */
internal fun FlatWorld.tiltOn(pos: BlockPos, state: BlockState): BlockSound? {
    if (!state.isDripleafLeaf) return null
    val (next, sound, wait) = when (state.properties[TILT]) {
        "unstable" -> Triple("partial_tilt", "tilt_down.big_dripleaf", TICKS_UNTIL_FULL_TILT)
        "partial_tilt" -> Triple("full_tilt", "tilt_down.big_dripleaf", TICKS_UNTIL_UPRIGHT)
        "full_tilt" -> Triple("none", "tilt_up.big_dripleaf", null)
        else -> return null
    }
    set(pos, blocks.find(BIG_DRIPLEAF, state.properties + (TILT to next)) ?: return null)
    wait?.let { schedule(pos, it, BlockUpdate.TILT) }
    return BlockSound(pos, sound, -1)
}
