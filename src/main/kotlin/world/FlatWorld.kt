package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.blockStateHash

/**
 * Chunks name biomes by number, and Cloudburst's data has no numbers for
 * vanilla biomes. So plains is hard-coded, as 1: the ID other servers send
 * it with.
 */
const val PLAINS_BIOME_ID = 1

/**
 * A world that's the same in every column: [layers] of blocks from the
 * bottom of the world up, air above them, and one biome throughout. Every
 * chunk is the same, so the world holds just one.
 */
class FlatWorld(layers: List<BlockState>, biome: Int = PLAINS_BIOME_ID) {
    /** Where players appear: standing on the top layer, in the corner of chunk 0, 0. */
    val spawn = BlockPos(0, MIN_Y + layers.size, 0)

    private val chunk: Chunk

    init {
        require(layers.size < SUB_CHUNK_COUNT * 16) { "${layers.size} layers leave no room to stand in the overworld" }

        val air = blockStateHash(AIR, emptyMap())
        // Every sub-chunk the layers reach. A block's y within its sub-chunk is the low four bits of its index.
        val subChunks = (0 until (layers.size + 15) / 16).map { subChunk ->
            PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { block -> layers.getOrNull(subChunk * 16 + (block and 0xF))?.networkId ?: air })
        }
        // The same storage all the way up, which goes out as one storage and then a byte for each repeat.
        val biomes = PalettedStorage.filled(biome)
        chunk = Chunk(subChunks, List(SUB_CHUNK_COUNT) { biomes })
    }

    fun chunkAt(pos: ChunkPos): Chunk = chunk

    companion object {
        /** The vanilla flat world: bedrock, two layers of dirt and grass on top, all plains. */
        fun vanilla(blocks: BlockPalette) = FlatWorld(
            listOf(
                blocks.stateOf("minecraft:bedrock", mapOf("infiniburn_bit" to 0.toByte())),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:grass_block"),
            ),
        )
    }
}
