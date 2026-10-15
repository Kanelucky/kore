package org.koremc.world

/** How many blocks a sub-chunk holds: 16 along each side. */
const val SUB_CHUNK_VOLUME = 4096

/** Where the block at [x], [y], [z] within a sub-chunk sits in its storage: x, then z, then y. */
fun blockIndex(x: Int, y: Int, z: Int) = (x shl 8) or (z shl 4) or y

/** The bits per palette index Bedrock understands, smallest first. */
private val BITS_PER_INDEX = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)

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

    /** The fewest bits per palette index Bedrock packs this storage's indices into: none for a single value. */
    val bitsPerIndex: Int get() = BITS_PER_INDEX.first { 1 shl it >= palette.size }

    /**
     * Each block's palette index, packed into 32-bit words as Bedrock sends
     * and saves them: [bitsPerIndex] each, as many as fit whole in a word,
     * the first in its lowest bits.
     */
    fun packedIndices(): IntArray {
        val bits = bitsPerIndex
        if (bits == 0) return IntArray(0)
        val perWord = 32 / bits
        return IntArray((SUB_CHUNK_VOLUME + perWord - 1) / perWord) { word ->
            val first = word * perWord
            (0 until minOf(perWord, SUB_CHUNK_VOLUME - first)).fold(0) { packed, i -> packed or (paletteIndex(first + i) shl (i * bits)) }
        }
    }

    /**
     * This storage with [value] at [block]. Chunks share storages, so this
     * one stays as it is. The palette is built afresh, dropping any value
     * no block has any more.
     */
    fun with(block: Int, value: Int): PalettedStorage = of(IntArray(SUB_CHUNK_VOLUME) { if (it == block) value else this[it] })

    /** This storage with each block in [values] set to the value it's paired with, built afresh once for them all. */
    fun with(values: Map<Int, Int>): PalettedStorage {
        val all = IntArray(SUB_CHUNK_VOLUME) { this[it] }
        for ((block, value) in values) all[block] = value
        return of(all)
    }

    companion object {
        /** The same value in every block. */
        fun filled(value: Int) = PalettedStorage(listOf(value), null)

        /**
         * The storage whose blocks' indices into [palette] are packed into
         * [words], [bits] each, as [packedIndices] packs them. An index
         * beyond the palette reads as its first value.
         */
        fun unpacked(bits: Int, words: IntArray, palette: List<Int>): PalettedStorage {
            if (bits == 0) return filled(palette[0])
            val perWord = 32 / bits
            val mask = (1 shl bits) - 1
            return of(
                IntArray(SUB_CHUNK_VOLUME) { block ->
                    val index = (words.getOrElse(block / perWord) { 0 } ushr ((block % perWord) * bits)) and mask
                    palette.getOrElse(index) { palette[0] }
                },
            )
        }

        /** A value for each block, in [blockIndex] order. */
        fun of(values: IntArray): PalettedStorage {
            require(values.size == SUB_CHUNK_VOLUME) { "A sub-chunk has $SUB_CHUNK_VOLUME blocks, not ${values.size}" }

            val palette = LinkedHashMap<Int, Int>()
            val indices = ShortArray(SUB_CHUNK_VOLUME) { block -> palette.getOrPut(values[block]) { palette.size }.toShort() }
            return if (palette.size == 1) filled(values[0]) else PalettedStorage(palette.keys.toList(), indices)
        }
    }
}
