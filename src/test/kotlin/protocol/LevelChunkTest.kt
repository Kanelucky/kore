package org.koremc.protocol

import io.netty.buffer.Unpooled
import org.koremc.world.BlockPos
import org.koremc.world.Chunk
import org.koremc.world.ChunkPos
import org.koremc.world.PalettedStorage
import org.koremc.world.SUB_CHUNK_COUNT
import org.koremc.world.SUB_CHUNK_VOLUME
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LevelChunkTest {
    private val plains = PalettedStorage.filled(1)

    @Test
    fun `a chunk packs its sub-chunks, then biomes for the full height, then border blocks`() {
        // Block 0 is the first palette entry (5) and every other block the second (9), so one bit each.
        val blocks = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { if (it == 0) 5 else 9 })
        val packet = LevelChunk(ChunkPos(1, -1), Chunk(listOf(blocks), List(SUB_CHUNK_COUNT) { plains })).encode()

        val subChunk = bytes(0x09, 0x01, 0xFC) + // Version 9, one storage layer, at height -4
            bytes(0x03) + // One bit per index, network palette
            bytes(0xFE, 0xFF, 0xFF, 0xFF) + ByteArray(127 * 4) { -1 } + // 128 words of 32 indices
            bytes(0x04, 0x0A, 0x12) // Two palette entries, 5 and 9, zigzagged
        // The first biome storage in full: no index bits, then plains (1). The other 23 repeat it.
        val biomes = bytes(0x01, 0x02) + ByteArray(SUB_CHUNK_COUNT - 1) { 0xFF.toByte() }
        val payload = subChunk + biomes + bytes(0x00)

        // ID 58, x 1 and z -1 zigzagged, the overworld, one sub-chunk, no request limit, no cache, no blob hashes.
        val header = bytes(0x3A, 0x02, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00)
        assertEquals(545, payload.size)
        assertContentEquals(header + bytes(0xA1, 0x04) + payload, packet)
    }

    @Test
    fun `indices that don't divide 32 bits pad each word instead of straddling two`() {
        // Five palette entries need three bits each: ten to a word, with two bits spare.
        val blocks = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { it % 5 })
        val packet = Unpooled.wrappedBuffer(LevelChunk(ChunkPos(0, 0), Chunk(listOf(blocks), List(SUB_CHUNK_COUNT) { plains })).encode())

        packet.skipBytes(8)
        packet.readVarUInt() // Payload length
        packet.skipBytes(3) // Sub-chunk header

        assertEquals((3 shl 1) or 1, packet.readUnsignedByte().toInt())
        assertEquals(0x23444688, packet.readIntLE())
        packet.skipBytes(408 * 4)
        assertEquals(0x4688, packet.readIntLE()) // The last word holds the six blocks left over
        assertEquals(5, packet.readVarInt())
        assertEquals(listOf(0, 1, 2, 3, 4), List(5) { packet.readVarInt() })
    }

    @Test
    fun `the publisher update centres the view in blocks and lists no saved chunks`() {
        val encoded = NetworkChunkPublisherUpdate(BlockPos(0, -60, 0), radius = 128).encode()

        // ID 121, the position as zigzagged varints, the radius as a varuint, then a little-endian zero count.
        assertContentEquals(bytes(0x79, 0x00, 0x77, 0x00, 0x80, 0x01, 0x00, 0x00, 0x00, 0x00), encoded)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
