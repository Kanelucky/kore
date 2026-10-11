package org.koremc.world

/** How many blocks a sub-chunk holds: 16 along each side. */
const val SUB_CHUNK_VOLUME = 4096

/** Where the block at [x], [y], [z] within a sub-chunk sits in its storage: x, then z, then y. */
fun blockIndex(x: Int, y: Int, z: Int) = (x shl 8) or (z shl 4) or y

/**
 * One value for every block of a sub-chunk, block states or biomes, kept
 * the way Bedrock sends and saves them: a palette of the distinct values,
 * and each block's index into it. Blocks are in [blockIndex] order.
 */
class PalettedStorage private constructor(
    /** Each distinct value once, in the order they first appear. */
    val palette: List<Int>,
    /** Each block's index into [palette], or null when every block has the same value. */
    private val indices: ShortArray?,
) {
    /** The index into [palette] of the value at [block]. */
    fun paletteIndex(block: Int): Int = indices?.get(block)?.toInt() ?: 0

    operator fun get(block: Int): Int = palette[paletteIndex(block)]

    companion object {
        /** The same value in every block. */
        fun filled(value: Int) = PalettedStorage(listOf(value), null)

        /** A value for each block, in [blockIndex] order. */
        fun of(values: IntArray): PalettedStorage {
            require(values.size == SUB_CHUNK_VOLUME) { "A sub-chunk has $SUB_CHUNK_VOLUME blocks, not ${values.size}" }

            val palette = LinkedHashMap<Int, Int>()
            val indices = ShortArray(SUB_CHUNK_VOLUME) { block -> palette.getOrPut(values[block]) { palette.size }.toShort() }
            return if (palette.size == 1) filled(values[0]) else PalettedStorage(palette.keys.toList(), indices)
        }
    }
}
