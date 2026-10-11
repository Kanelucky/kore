package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.world.BlockPos
import org.koremc.world.Chunk
import org.koremc.world.ChunkPos
import org.koremc.world.MIN_Y
import org.koremc.world.PalettedStorage
import org.koremc.world.SUB_CHUNK_VOLUME

private const val LEVEL_CHUNK = 58
private const val NETWORK_CHUNK_PUBLISHER_UPDATE = 121

private const val OVERWORLD = 0

/** The sub-chunk format in which each sub-chunk names its own height. */
private const val SUB_CHUNK_VERSION = 9

/** The bits per palette index Bedrock understands, smallest first. */
private val BITS_PER_INDEX = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 16)

/** Marks a storage that repeats the one before it, sent instead of the storage. Only biomes use it. */
private const val REPEAT_PREVIOUS = (0x7F shl 1) or 1

/**
 * A whole chunk column in one packet: every sub-chunk up to the last with
 * anything in it, then biomes for the column's full height, then the
 * column's [blockEntities]. The client can also ask for sub-chunks one by
 * one, but Kore doesn't make it.
 */
data class LevelChunk(val position: ChunkPos, val chunk: Chunk, val blockEntities: Collection<NbtMap> = emptyList()) : ServerPacket {
    override val id get() = LEVEL_CHUNK

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(position.x)
        out.writeVarInt(position.z)
        out.writeVarInt(OVERWORLD)
        out.writeVarUInt(chunk.subChunks.size)
        out.writeBoolean(false) // No sub-chunk request limit, as the sub-chunks are all here
        out.writeBoolean(false) // Blob cache
        out.writeVarUInt(0) // Blob hashes, which only the cache uses

        val payload = Unpooled.buffer()
        chunk.subChunks.forEachIndexed { i, blocks -> payload.writeSubChunk(MIN_Y / 16 + i, blocks) }
        chunk.biomes.forEachIndexed { i, biomes -> payload.writePalettedStorage(biomes, previous = chunk.biomes.getOrNull(i - 1)) }
        payload.writeByte(0) // Border blocks, an Education Edition feature
        blockEntities.forEach(payload::writeNbt) // One after another, until the payload ends

        out.writeVarUInt(payload.readableBytes())
        out.writeBytes(payload)
    }
}

/**
 * Where the player's view is centred and how far it reaches, in blocks.
 * The client keeps the chunks inside it, and drops the rest.
 */
data class NetworkChunkPublisherUpdate(val position: BlockPos, val radius: Int) : ServerPacket {
    override val id get() = NETWORK_CHUNK_PUBLISHER_UPDATE

    override fun encodeBody(out: ByteBuf) {
        out.writeBlockPos(position)
        out.writeVarUInt(radius)
        out.writeIntLE(0) // Chunks the server built for the client to save, counted without a varint
    }
}

private fun ByteBuf.writeSubChunk(index: Int, blocks: PalettedStorage) {
    writeByte(SUB_CHUNK_VERSION)
    writeByte(1) // Storage layers: just blocks, with no second layer for water inside them
    writeByte(index) // Its height, in sub-chunks, so the lowest overworld sub-chunk is -4
    writePalettedStorage(blocks, previous = null)
}

/**
 * [storage] in the network format: a header byte with the bits per palette
 * index, then the indices packed into little-endian 32-bit words, as many
 * as fit whole, then the palette as varints. A single-value palette needs
 * no indices and no count.
 */
private fun ByteBuf.writePalettedStorage(storage: PalettedStorage, previous: PalettedStorage?) {
    if (storage === previous) {
        writeByte(REPEAT_PREVIOUS)
        return
    }

    val bits = BITS_PER_INDEX.first { 1 shl it >= storage.palette.size }
    writeByte((bits shl 1) or 1) // The low bit says the palette holds network IDs, not NBT
    if (bits > 0) {
        val perWord = 32 / bits
        for (first in 0 until SUB_CHUNK_VOLUME step perWord) {
            var word = 0
            for (i in 0 until minOf(perWord, SUB_CHUNK_VOLUME - first)) word = word or (storage.paletteIndex(first + i) shl (i * bits))
            writeIntLE(word)
        }
        writeVarInt(storage.palette.size)
    }
    storage.palette.forEach { writeVarInt(it) }
}
