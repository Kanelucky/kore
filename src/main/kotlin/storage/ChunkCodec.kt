package org.koremc.storage

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.registry.AIR
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.world.MIN_Y
import org.koremc.world.PLAINS_BIOME_ID
import org.koremc.world.PalettedStorage
import org.koremc.world.SUB_CHUNK_COUNT
import org.koremc.world.SUB_CHUNK_VOLUME

/** The sub-chunk format the game saves, in which each sub-chunk names its own height. */
private const val SUB_CHUNK_FORMAT = 9

/** The format before it, the same but for the height. */
private const val UNNAMED_SUB_CHUNK_FORMAT = 8

/** How the game marks a biome storage that's the same as the one under it. */
private const val SAME_AS_BELOW = 0x7F

/** The height map the game keeps at the front of a chunk's biomes: a short for each column, which it works out again. */
private const val HEIGHT_MAP_BYTES = 16 * 16 * 2

/** The height, in sub-chunks, of the sub-chunk at [index] up from the bottom of the overworld. */
internal fun subChunkHeight(index: Int) = MIN_Y / 16 + index

/**
 * A sub-chunk's [blocks] as the game saves them: the format, one storage
 * layer, its [height] in sub-chunks, then the blocks' palette indices,
 * packed, and the palette, each state as little-endian NBT, by [palette].
 */
internal fun encodeSubChunk(height: Int, blocks: PalettedStorage, palette: BlockPalette): ByteArray {
    val out = Unpooled.buffer()
    out.writeByte(SUB_CHUNK_FORMAT).writeByte(1).writeByte(height)
    out.writeStorage(blocks) { networkId ->
        val state = palette[networkId] ?: palette.stateOf(AIR)
        val entry = NbtMap.builder().putString("name", state.name).putCompound("states", state.properties).putInt("version", state.version).build()
        NbtUtils.createWriterLE(ByteBufOutputStream(this)).writeTag(entry)
    }
    return ByteBufUtil.getBytes(out)
}

/**
 * The blocks of a sub-chunk the game saved as [bytes], its first storage
 * layer, which holds the blocks; a second holds the water inside them,
 * which Kore leaves out. A state [palette] doesn't know is its block's
 * default, or air for a block it doesn't know either. Null for a format
 * from before 1.2.13, which Kore can't read.
 */
internal fun decodeSubChunk(bytes: ByteArray, palette: BlockPalette): PalettedStorage? {
    val input = Unpooled.wrappedBuffer(bytes)
    val format = input.readUnsignedByte().toInt()
    val layers = when (format) {
        1 -> 1
        UNNAMED_SUB_CHUNK_FORMAT -> input.readUnsignedByte().toInt()
        SUB_CHUNK_FORMAT -> input.readUnsignedByte().toInt().also { input.readByte() } // Then its height, which its key gives too
        else -> return null
    }
    if (layers == 0) return PalettedStorage.filled(AIR_NETWORK_ID)
    return input.readStorage { blockFrom(NbtUtils.createReaderLE(ByteBufInputStream(this)).readTag() as NbtMap, palette) }
}

/** The network ID of the state the game saved as [entry]: its name and properties, or its block's default, or air. */
private fun blockFrom(entry: NbtMap, palette: BlockPalette): Int {
    val name = entry.getString("name")
    val state = palette.find(name, entry.getCompound("states", NbtMap.EMPTY)) ?: palette.defaultOf(name)
    return state?.networkId ?: AIR_NETWORK_ID
}

/**
 * A chunk's [biomes] as the game saves them, after a height map it works
 * out again: a storage for each sub-chunk, from the bottom of the world
 * up, each biome a little-endian int, or a mark for one the same as the
 * one under it.
 */
internal fun encodeBiomes(biomes: List<PalettedStorage>): ByteArray {
    val out = Unpooled.buffer()
    out.writeZero(HEIGHT_MAP_BYTES)
    biomes.forEachIndexed { i, storage ->
        if (i > 0 && storage === biomes[i - 1]) out.writeByte(SAME_AS_BELOW shl 1) else out.writeStorage(storage) { writeIntLE(it) }
    }
    return ByteBufUtil.getBytes(out)
}

/**
 * The biomes of a chunk the game saved as [bytes], a storage for each
 * sub-chunk of the overworld. Any it didn't save, above the last, are the
 * same as that one, or plains if it saved none.
 */
internal fun decodeBiomes(bytes: ByteArray): List<PalettedStorage> {
    val input = Unpooled.wrappedBuffer(bytes)
    input.skipBytes(minOf(HEIGHT_MAP_BYTES, input.readableBytes()))
    val biomes = mutableListOf<PalettedStorage>()
    while (input.isReadable && biomes.size < SUB_CHUNK_COUNT) {
        if (input.getUnsignedByte(input.readerIndex()).toInt() shr 1 == SAME_AS_BELOW) {
            input.skipBytes(1)
            biomes += biomes.lastOrNull() ?: PalettedStorage.filled(PLAINS_BIOME_ID)
            continue
        }
        biomes += input.readStorage { readIntLE() }
    }
    val last = biomes.lastOrNull() ?: PalettedStorage.filled(PLAINS_BIOME_ID)
    while (biomes.size < SUB_CHUNK_COUNT) biomes += last
    return biomes
}

/**
 * [storage] as the game saves one: a header with the bits per palette
 * index, low bit clear for a palette that isn't network IDs, the indices
 * packed into little-endian ints, then for more than one value its size,
 * and each value as [writeValue] writes it.
 */
private fun ByteBuf.writeStorage(storage: PalettedStorage, writeValue: ByteBuf.(Int) -> Unit) {
    val bits = storage.bitsPerIndex
    writeByte(bits shl 1)
    if (bits > 0) {
        storage.packedIndices().forEach(::writeIntLE)
        writeIntLE(storage.palette.size)
    }
    storage.palette.forEach { writeValue(it) }
}

/** A storage the game saved, as [writeStorage] writes one, each value as [readValue] reads it. */
private fun ByteBuf.readStorage(readValue: ByteBuf.() -> Int): PalettedStorage {
    val bits = readUnsignedByte().toInt() shr 1
    val words = if (bits == 0) IntArray(0) else IntArray((SUB_CHUNK_VOLUME + 32 / bits - 1) / (32 / bits)) { readIntLE() }
    val size = if (bits == 0) 1 else readIntLE()
    val palette = List(size) { readValue() }
    return PalettedStorage.unpacked(bits, words, palette)
}
