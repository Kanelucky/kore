package org.koremc.world

import org.koremc.registry.AIR_NETWORK_ID

/** The overworld's floor. It runs 24 sub-chunks up from there, to y 319. Kore has no other dimension yet. */
const val MIN_Y = -64
const val SUB_CHUNK_COUNT = 24

/** The overworld's highest block. */
const val MAX_Y = MIN_Y + SUB_CHUNK_COUNT * 16 - 1

/**
 * A column of the world, 16 blocks square and as tall as the overworld.
 * [subChunks] hold block network IDs, which are block state hashes, from
 * the bottom up; any above the last one are air. [biomes] has a biome ID
 * for every block, one storage per sub-chunk, all the way up. [liquids]
 * is the second layer of each sub-chunk, as the game keeps it: the water
 * inside blocks, like a fence standing in water, or null for a sub-chunk
 * with none.
 */
class Chunk(val subChunks: List<PalettedStorage>, val biomes: List<PalettedStorage>, val liquids: List<PalettedStorage?> = emptyList()) {
    init {
        require(subChunks.size <= SUB_CHUNK_COUNT) { "${subChunks.size} sub-chunks don't fit in the overworld's $SUB_CHUNK_COUNT" }
        require(biomes.size == SUB_CHUNK_COUNT) { "Biomes cover all $SUB_CHUNK_COUNT sub-chunks, not ${biomes.size}" }
    }

    /** The network ID of the block at [pos], which must be in this column. Above and below the overworld, there's only air. */
    fun blockAt(pos: BlockPos): Int = subChunks.getOrNull(subChunkOf(pos))?.get(indexOf(pos)) ?: AIR_NETWORK_ID

    /** The network ID of what's in the second layer at [pos]: the water inside the block there, or air. */
    fun liquidAt(pos: BlockPos): Int = liquids.getOrNull(subChunkOf(pos))?.get(indexOf(pos)) ?: AIR_NETWORK_ID

    /**
     * This column with [networkId] at [pos]. Columns share sub-chunks, so
     * this one stays as it is. Sub-chunks are added up to the block, and
     * any left with nothing but air at the top are dropped.
     */
    fun with(pos: BlockPos, networkId: Int): Chunk {
        val subChunks = subChunks.toMutableList()
        while (subChunks.size <= subChunkOf(pos)) subChunks += PalettedStorage.filled(AIR_NETWORK_ID)
        subChunks[subChunkOf(pos)] = subChunks[subChunkOf(pos)].with(indexOf(pos), networkId)
        while (subChunks.lastOrNull()?.palette == listOf(AIR_NETWORK_ID)) subChunks.removeAt(subChunks.lastIndex)
        return Chunk(subChunks, biomes, liquids.take(subChunks.size))
    }

    /** This column with each block in [blocks] the network ID it's paired with: each sub-chunk built afresh once, for all of its own. */
    fun with(blocks: Map<BlockPos, Int>): Chunk {
        val subChunks = subChunks.toMutableList()
        for ((index, inSubChunk) in blocks.entries.groupBy { subChunkOf(it.key) }) {
            while (subChunks.size <= index) subChunks += PalettedStorage.filled(AIR_NETWORK_ID)
            subChunks[index] = subChunks[index].with(inSubChunk.associate { (pos, networkId) -> indexOf(pos) to networkId })
        }
        while (subChunks.lastOrNull()?.palette == listOf(AIR_NETWORK_ID)) subChunks.removeAt(subChunks.lastIndex)
        return Chunk(subChunks, biomes, liquids.take(subChunks.size))
    }

    /** This column with [networkId] in the second layer at [pos], inside the block there. A layer left with nothing but air goes. */
    fun withLiquid(pos: BlockPos, networkId: Int): Chunk {
        val liquids = liquids.toMutableList()
        while (liquids.size <= subChunkOf(pos)) liquids += null
        val layer = (liquids[subChunkOf(pos)] ?: PalettedStorage.filled(AIR_NETWORK_ID)).with(indexOf(pos), networkId)
        liquids[subChunkOf(pos)] = layer.takeUnless { it.palette == listOf(AIR_NETWORK_ID) }
        while (liquids.isNotEmpty() && liquids.last() == null) liquids.removeAt(liquids.lastIndex)
        return Chunk(subChunks, biomes, liquids)
    }

    private fun subChunkOf(pos: BlockPos) = (pos.y - MIN_Y) shr 4

    // The overworld's floor is a whole number of sub-chunks down, so the low four bits place a block within its sub-chunk.
    private fun indexOf(pos: BlockPos) = blockIndex(pos.x and 0xF, pos.y and 0xF, pos.z and 0xF)
}

/**
 * Every chunk within [radius] chunks of [center], nearest first. The client
 * measures its view distance as a circle, so this does too.
 */
fun chunksAround(center: ChunkPos, radius: Int): List<ChunkPos> =
    (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> dx to dz } }
        .filter { (dx, dz) -> dx * dx + dz * dz <= radius * radius }
        .sortedBy { (dx, dz) -> dx * dx + dz * dz }
        .map { (dx, dz) -> ChunkPos(center.x + dx, center.z + dz) }
