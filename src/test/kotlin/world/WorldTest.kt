package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.blockStateHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorldTest {
    @Test
    fun `storages keep each distinct value once, in the order they first appear`() {
        val storage = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { if (it % 3 == 0) 40 else 7 })

        assertEquals(listOf(40, 7), storage.palette)
        assertEquals(40, storage[0])
        assertEquals(7, storage[1])
        assertEquals(0, storage.paletteIndex(3))
        assertEquals(1, storage.paletteIndex(4))
    }

    @Test
    fun `a storage with one value needs no indices`() {
        val storage = PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { 9 })

        assertEquals(listOf(9), storage.palette)
        assertEquals(9, storage[blockIndex(15, 15, 15)])
        assertFailsWith<IllegalArgumentException> { PalettedStorage.of(IntArray(16)) }
    }

    @Test
    fun `blocks are indexed x, then z, then y`() {
        assertEquals(0, blockIndex(0, 0, 0))
        assertEquals(1, blockIndex(0, 1, 0))
        assertEquals(16, blockIndex(0, 0, 1))
        assertEquals(256, blockIndex(1, 0, 0))
    }

    @Test
    fun `points round down to the block they're in`() {
        assertEquals(BlockPos(-1, -59, 15), Vec3(-0.5f, -58.38f, 15.99f).toBlockPos())
        assertEquals(ChunkPos(-1, 0), Vec3(-0.5f, 0f, 15.99f).toBlockPos().chunk)
    }

    @Test
    fun `the view is a circle of chunks, nearest first`() {
        val center = ChunkPos(3, -2)

        assertEquals(listOf(center), chunksAround(center, 0))
        assertEquals(13, chunksAround(center, 2).size)
        assertEquals(29, chunksAround(center, 3).size)
        assertEquals(center, chunksAround(center, 3).first())
        assertTrue(ChunkPos(5, 0) !in chunksAround(center, 2)) // Diagonal, so outside the circle
        assertEquals(ChunkPos(0, -1), BlockPos(15, 70, -1).chunk)
    }

    @Test
    fun `the vanilla flat world is bedrock, dirt and grass, with players standing on the grass`() {
        val bedrock = BlockState("minecraft:bedrock", NbtMap.builder().putByte("infiniburn_bit", 0).build(), VERSION)
        val dirt = BlockState("minecraft:dirt", NbtMap.EMPTY, VERSION)
        val grass = BlockState("minecraft:grass_block", NbtMap.EMPTY, VERSION)
        val world = FlatWorld.vanilla(BlockPalette(listOf(bedrock, dirt, grass)))

        val chunk = world.chunkAt(ChunkPos(-7, 12))
        val column = (0 until 6).map { y -> chunk.subChunks.single()[blockIndex(4, y, 9)] }

        assertEquals(BlockPos(0, -60, 0), world.spawn)
        assertEquals(listOf(bedrock, dirt, dirt, grass).map { it.networkId } + List(2) { blockStateHash(AIR, emptyMap()) }, column)
        assertEquals(List(SUB_CHUNK_COUNT) { listOf(PLAINS_BIOME_ID) }, chunk.biomes.map { it.palette })
        assertSame(chunk, world.chunkAt(ChunkPos(0, 0)))
    }

    @Test
    fun `layers can reach past the first sub-chunk`() {
        val stone = BlockState("minecraft:stone", NbtMap.EMPTY, VERSION)
        val world = FlatWorld(List(20) { stone })

        val chunk = world.chunkAt(ChunkPos(0, 0))

        assertEquals(2, chunk.subChunks.size)
        assertEquals(listOf(stone.networkId), chunk.subChunks[0].palette)
        assertEquals(stone.networkId, chunk.subChunks[1][blockIndex(0, 3, 0)])
        assertEquals(blockStateHash(AIR, emptyMap()), chunk.subChunks[1][blockIndex(0, 4, 0)])
        assertEquals(BlockPos(0, -44, 0), world.spawn)
    }

    private companion object {
        const val VERSION = 18168865
    }
}
