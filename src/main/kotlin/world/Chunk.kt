package org.koremc.world

/** The overworld's floor. It runs 24 sub-chunks up from there, to y 319. Kore has no other dimension yet. */
const val MIN_Y = -64
const val SUB_CHUNK_COUNT = 24

/**
 * A column of the world, 16 blocks square and as tall as the overworld.
 * [subChunks] hold block network IDs, which are block state hashes, from
 * the bottom up; any above the last one are air. [biomes] has a biome ID
 * for every block, one storage per sub-chunk, all the way up.
 */
class Chunk(val subChunks: List<PalettedStorage>, val biomes: List<PalettedStorage>) {
    init {
        require(subChunks.size <= SUB_CHUNK_COUNT) { "${subChunks.size} sub-chunks don't fit in the overworld's $SUB_CHUNK_COUNT" }
        require(biomes.size == SUB_CHUNK_COUNT) { "Biomes cover all $SUB_CHUNK_COUNT sub-chunks, not ${biomes.size}" }
    }
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
