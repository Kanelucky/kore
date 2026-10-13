package org.koremc.storage

import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.registry.AIR
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.world.PLAINS_BIOME_ID
import org.koremc.world.PalettedStorage
import org.koremc.world.SUB_CHUNK_COUNT
import org.koremc.world.SUB_CHUNK_VOLUME
import org.koremc.world.blockIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChunkCodecTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val stone = state("minecraft:stone")
    private val log = state("minecraft:oak_log", "pillar_axis" to "y")
    private val sideways = state("minecraft:oak_log", "pillar_axis" to "x")
    private val palette = BlockPalette(listOf(state(AIR), stone, log, sideways))

    private fun values(storage: PalettedStorage) = List(SUB_CHUNK_VOLUME) { storage[it] }

    @Test
    fun `a sub-chunk saves as the game's format 9, with each state in NBT, and reads back as it was`() {
        val blocks = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { if (it % 3 == 0) stone.networkId else if (it % 7 == 0) log.networkId else AIR_NETWORK_ID })
        val bytes = encodeSubChunk(-2, blocks, palette)
        assertEquals(listOf(9, 1, 0xFE), bytes.take(3).map { it.toInt() and 0xFF })
        assertEquals(2 shl 1, bytes[3].toInt(), "two bits for three values, the low bit clear for NBT")

        assertEquals(values(blocks), values(decodeSubChunk(bytes, palette)!!))
        val filled = PalettedStorage.filled(stone.networkId)
        assertEquals(values(filled), values(decodeSubChunk(encodeSubChunk(0, filled, palette), palette)!!))
    }

    @Test
    fun `a state Kore doesn't know reads as its block's default, a block it doesn't know as air, and only the first storage layer counts`() {
        val out = Unpooled.buffer().writeByte(8).writeByte(2) // Format 8, two layers
        out.writeByte(1 shl 1)
        repeat(SUB_CHUNK_VOLUME / 32) { out.writeIntLE(if (it == 0) 1 else 0) }
        out.writeIntLE(2)
        writeNbt(out, NbtMap.builder().putString("name", "minecraft:oak_log").putCompound("states", NbtMap.builder().putString("pillar_axis", "q").build()).build())
        writeNbt(out, NbtMap.builder().putString("name", "minecraft:no_such_block").putCompound("states", NbtMap.EMPTY).build())
        out.writeByte(0) // The water layer, one value
        writeNbt(out, NbtMap.builder().putString("name", "minecraft:water").putCompound("states", NbtMap.EMPTY).build())

        val blocks = decodeSubChunk(ByteBufUtil.getBytes(out), palette)!!
        assertEquals(AIR_NETWORK_ID, blocks[blockIndex(0, 0, 0)], "a block Kore doesn't know")
        assertEquals(log.networkId, blocks[blockIndex(0, 1, 0)], "a state it doesn't know, its block's default")
        assertEquals(setOf(log.networkId, AIR_NETWORK_ID), (0 until SUB_CHUNK_VOLUME).map { blocks[it] }.toSet())
        assertNull(decodeSubChunk(byteArrayOf(4, 0), palette), "a format from before 1.2.13")
    }

    @Test
    fun `biomes save after a height map, a storage repeated over the one under it as a mark, and read back up to the top of the world`() {
        val plains = PalettedStorage.filled(PLAINS_BIOME_ID)
        val mixed = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { if (it < 100) 2 else PLAINS_BIOME_ID })
        val biomes = listOf(plains, plains, mixed) + List(SUB_CHUNK_COUNT - 3) { mixed }
        val bytes = encodeBiomes(biomes)
        assertEquals(0, bytes.take(512).count { it != 0.toByte() })
        assertEquals(0, bytes[512].toInt(), "the first, one value")
        assertEquals(PLAINS_BIOME_ID, bytes[513].toInt())
        assertEquals(0xFE, bytes[517].toInt() and 0xFF, "the second, the same as the first")

        val read = decodeBiomes(bytes)
        assertEquals(SUB_CHUNK_COUNT, read.size)
        assertEquals(biomes.map(::values), read.map(::values))

        // Saved with only two, the rest are the same as the last.
        val short = decodeBiomes(encodeBiomes(listOf(plains, mixed)))
        assertEquals(SUB_CHUNK_COUNT, short.size)
        assertSame(short[1], short.last())
    }

    private fun writeNbt(out: io.netty.buffer.ByteBuf, tag: NbtMap) = NbtUtils.createWriterLE(ByteBufOutputStream(out)).writeTag(tag)
}
