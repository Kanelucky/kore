package org.koremc.world

import org.koremc.registry.BlockState
import kotlin.math.abs
import kotlin.math.max

/** The brightest light gets: the sky's, and the brightest blocks'. */
internal const val MAX_LIGHT = 15

/** A column whose [skyTop] hasn't been worked out yet. */
private const val UNKNOWN_TOP = Int.MIN_VALUE

/**
 * How much of the light reaching [state] it stops, from none to all of it:
 * as the game says, or without its word, as in tests, all of it for a
 * whole block that lets none through.
 */
internal fun FlatWorld.dampening(state: BlockState): Int = when {
    state.networkId in traits -> traits[state.networkId].lightDampening
    state.isFullCube && !state.letsLightThrough -> MAX_LIGHT
    else -> 0
}

/** How much of the light reaching [pos] the block there stops, or the water inside it. */
private fun FlatWorld.dampeningAt(pos: BlockPos): Int = max(stateAt(pos)?.let(::dampening) ?: 0, liquidAt(pos)?.let(::dampening) ?: 0)

/** A block giving off light, and how bright, which Kore keeps for each chunk till a block in it changes. */
internal class LightSource(val pos: BlockPos, val level: Int)

/** The blocks giving off light in the chunk at [chunk]. */
private fun FlatWorld.lightSourcesIn(chunk: ChunkPos): List<LightSource> = lightSources.getOrPut(chunk) {
    buildList {
        for ((index, storage) in chunkAt(chunk).subChunks.withIndex()) {
            if (storage.palette.none { traits[it].lightEmission > 0 }) continue
            for (block in 0 until SUB_CHUNK_VOLUME) {
                val level = traits[storage[block]].lightEmission
                // Blocks are stored x, then z, then y, each in four bits.
                if (level > 0) add(LightSource(BlockPos(chunk.x * 16 + (block shr 8), MIN_Y + index * 16 + (block and 0xF), chunk.z * 16 + (block shr 4 and 0xF)), level))
            }
        }
    }
}

/**
 * The height of the highest block in the column at [x], [z] that stops
 * any light, below which the sky's light doesn't fall straight down
 * whole. Kore keeps them for each chunk till a block in it changes.
 */
private fun FlatWorld.skyTop(x: Int, z: Int): Int {
    val tops = skyTops.getOrPut(ChunkPos(x shr 4, z shr 4)) { IntArray(16 * 16) { UNKNOWN_TOP } }
    val column = (x and 0xF) shl 4 or (z and 0xF)
    if (tops[column] == UNKNOWN_TOP) {
        val top = MIN_Y + chunkAt(ChunkPos(x shr 4, z shr 4)).subChunks.size * 16 - 1
        tops[column] = (top downTo MIN_Y).firstOrNull { y -> dampeningAt(BlockPos(x, y, z)) > 0 } ?: (MIN_Y - 1)
    }
    return tops[column]
}

/** Whether the sky's light falls on [pos] whole: nothing above it, or at it, stops any. */
private fun FlatWorld.underOpenSky(pos: BlockPos) = pos.y > skyTop(pos.x, pos.z)

/**
 * Looks outward from [pos] for the light that reaches it, nearest first in
 * the light lost on the way: a step into a block loses a level of light,
 * or as much as the block stops, if that's more, and a block that stops
 * all of it lets none on. [from] says how bright the light is at each place,
 * none of it brighter than [brightest], and the brightest that reaches
 * [pos], less what it loses on the way, is its light.
 */
private inline fun FlatWorld.lightReaching(pos: BlockPos, brightest: Int, from: (BlockPos) -> Int): Int {
    var best = 0
    val lost = HashMap<BlockPos, Int>()
    val byLoss = Array(MAX_LIGHT) { ArrayDeque<BlockPos>() }
    lost[pos] = 0
    byLoss[0] += pos
    var loss = 0
    // Nothing further than this could be brighter than what's been found.
    while (loss < brightest - best) {
        val at = byLoss[loss].removeFirstOrNull()
        if (at == null) {
            loss++
            continue
        }
        if (lost[at] != loss) continue
        best = max(best, from(at) - loss)
        // Light coming this way loses some going into this block.
        val next = loss + max(1, dampeningAt(at))
        if (next >= brightest - best) continue
        for (face in Face.entries) {
            val beyond = at.side(face)
            if (beyond.y !in MIN_Y..MAX_Y || next >= (lost[beyond] ?: MAX_LIGHT)) continue
            lost[beyond] = next
            byLoss[next] += beyond
        }
    }
    return best
}

/**
 * The sky's light at [pos], from 0 to 15, whatever the time: all of it
 * under nothing that stops any, falling straight down, and less of it the
 * further it spreads from there, as light does, or the more it goes
 * through, like leaves or water. A block that stops all light has none.
 */
fun FlatWorld.skyLightAt(pos: BlockPos): Int {
    if (pos.y > MAX_Y) return MAX_LIGHT
    if (pos.y < MIN_Y || dampeningAt(pos) >= MAX_LIGHT) return 0
    return lightReaching(pos, MAX_LIGHT) { if (underOpenSky(it)) MAX_LIGHT else 0 }
}

/**
 * The light blocks give off at [pos], from 0 to 15: from the brightest
 * nearby, a torch's 14, say, less a level for each step from it, and more
 * through blocks that stop some, like water.
 */
fun FlatWorld.blockLightAt(pos: BlockPos): Int {
    if (pos.y !in MIN_Y..MAX_Y) return 0
    // Light reaches no further than its level, so only the chunks beside this one can hold any that reaches it.
    val near = HashMap<BlockPos, Int>()
    for (dx in -1..1) for (dz in -1..1) {
        for (source in lightSourcesIn(ChunkPos(pos.chunk.x + dx, pos.chunk.z + dz))) {
            if (source.level > abs(source.pos.x - pos.x) + abs(source.pos.y - pos.y) + abs(source.pos.z - pos.z)) near[source.pos] = source.level
        }
    }
    if (near.isEmpty()) return 0
    if (dampeningAt(pos) >= MAX_LIGHT) return near[pos] ?: 0
    return lightReaching(pos, near.values.max()) { near[it] ?: 0 }
}

/** The light at [pos]: the sky's or the blocks', whichever is brighter, whatever the time. */
fun FlatWorld.lightAt(pos: BlockPos): Int = max(skyLightAt(pos), blockLightAt(pos))

/**
 * The light at [pos] as the game reckons it for what grows: the blocks',
 * or the sky's as dark as the time and weather make it, whichever is
 * brighter. Out in the open, that's 15 at noon and 4 at midnight.
 */
fun FlatWorld.internalLightAt(pos: BlockPos): Int = max(blockLightAt(pos), skyLightAt(pos) - skyDarkening())
