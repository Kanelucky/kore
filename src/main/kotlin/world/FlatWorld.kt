package org.koremc.world

import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState

/**
 * Chunks name biomes by number, and Cloudburst's data has no numbers for
 * vanilla biomes. So plains is hard-coded, as 1: the ID other servers send
 * it with.
 */
const val PLAINS_BIOME_ID = 1

/**
 * A world that starts out the same in every column: [layers] of blocks
 * from the bottom of the world up, air above them, and one biome
 * throughout. Until a player changes a block in a chunk, the chunk is that
 * same column, so the world holds just one of those, plus each chunk that
 * players have changed. Changes last until the server stops.
 *
 * Like the sessions that change it, it runs on the UDP event loop.
 */
class FlatWorld(
    /** Every block state the world can hold, by network ID. */
    val blocks: BlockPalette,
    layers: List<BlockState>,
    biome: Int = PLAINS_BIOME_ID,
) {
    /** Where players appear: standing on the top layer, in the corner of chunk 0, 0. */
    val spawn = BlockPos(0, MIN_Y + layers.size, 0)

    private val generated: Chunk

    /** The chunks players have changed, which no longer match [generated]. */
    private val changed = HashMap<ChunkPos, Chunk>()

    init {
        require(layers.size < SUB_CHUNK_COUNT * 16) { "${layers.size} layers leave no room to stand in the overworld" }

        // Every sub-chunk the layers reach. A block's y within its sub-chunk is the low four bits of its index.
        val subChunks = (0 until (layers.size + 15) / 16).map { subChunk ->
            PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { block -> layers.getOrNull(subChunk * 16 + (block and 0xF))?.networkId ?: AIR_NETWORK_ID })
        }
        // The same storage all the way up, which goes out as one storage and then a byte for each repeat.
        val biomes = PalettedStorage.filled(biome)
        generated = Chunk(subChunks, List(SUB_CHUNK_COUNT) { biomes })
    }

    fun chunkAt(pos: ChunkPos): Chunk = changed[pos] ?: generated

    /** The network ID of the block at [pos]. */
    fun blockAt(pos: BlockPos): Int = chunkAt(pos.chunk).blockAt(pos)

    /** The block at [pos], or null if it's one [blocks] doesn't have. */
    fun stateAt(pos: BlockPos): BlockState? = blocks[blockAt(pos)]

    /** Puts [state] at [pos], which must be within the overworld's height. */
    operator fun set(pos: BlockPos, state: BlockState) {
        require(pos.y in MIN_Y..MAX_Y) { "$pos is outside the overworld" }
        changed[pos.chunk] = chunkAt(pos.chunk).with(pos, state.networkId)
    }

    companion object {
        /** The vanilla flat world: bedrock, two layers of dirt and grass on top, all plains. */
        fun vanilla(blocks: BlockPalette) = FlatWorld(
            blocks,
            listOf(
                blocks.stateOf("minecraft:bedrock", mapOf("infiniburn_bit" to 0.toByte())),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:grass_block"),
            ),
        )
    }
}
