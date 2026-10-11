package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.AIR_NETWORK_ID
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
        val world = FlatWorld.vanilla(BlockPalette(listOf(air, bedrock, dirt, grass)))

        val chunk = world.chunkAt(ChunkPos(-7, 12))
        val column = (0 until 6).map { y -> chunk.subChunks.single()[blockIndex(4, y, 9)] }

        assertEquals(BlockPos(0, -60, 0), world.spawn)
        assertEquals(listOf(bedrock, dirt, dirt, grass).map { it.networkId } + List(2) { blockStateHash(AIR, emptyMap()) }, column)
        assertEquals(List(SUB_CHUNK_COUNT) { listOf(PLAINS_BIOME_ID) }, chunk.biomes.map { it.palette })
        assertSame(chunk, world.chunkAt(ChunkPos(0, 0)))
    }

    @Test
    fun `layers can reach past the first sub-chunk`() {
        val world = FlatWorld(BlockPalette(listOf(air, stone)), List(20) { stone })

        val chunk = world.chunkAt(ChunkPos(0, 0))

        assertEquals(2, chunk.subChunks.size)
        assertEquals(listOf(stone.networkId), chunk.subChunks[0].palette)
        assertEquals(stone.networkId, chunk.subChunks[1][blockIndex(0, 3, 0)])
        assertEquals(blockStateHash(AIR, emptyMap()), chunk.subChunks[1][blockIndex(0, 4, 0)])
        assertEquals(BlockPos(0, -44, 0), world.spawn)
    }

    @Test
    fun `changing a storage leaves the original as it was`() {
        val storage = PalettedStorage.filled(9)

        val changed = storage.with(blockIndex(1, 2, 3), 40)

        assertEquals(listOf(9, 40), changed.palette)
        assertEquals(40, changed[blockIndex(1, 2, 3)])
        assertEquals(listOf(9), storage.palette)
        assertEquals(listOf(9), changed.with(blockIndex(1, 2, 3), 9).palette) // Values no block has any more go
    }

    @Test
    fun `a changed block changes its chunk, and no other`() {
        val world = FlatWorld(BlockPalette(listOf(air, stone)), listOf(stone))
        val untouched = world.chunkAt(ChunkPos(5, 5))

        world[BlockPos(-1, -64, 17)] = air

        assertEquals(AIR_NETWORK_ID, world.blockAt(BlockPos(-1, -64, 17)))
        assertEquals(air, world.stateAt(BlockPos(-1, -64, 17)))
        assertEquals(stone.networkId, world.blockAt(BlockPos(-2, -64, 17)))
        assertEquals(stone.networkId, world.blockAt(BlockPos(-1, -64, 16))) // The next chunk south
        assertSame(untouched, world.chunkAt(ChunkPos(5, 5)))
        assertEquals(AIR_NETWORK_ID, world.blockAt(BlockPos(0, MAX_Y + 1, 0)))
    }

    @Test
    fun `a block placed high adds sub-chunks up to it, and they go once only air is left`() {
        val world = FlatWorld(BlockPalette(listOf(air, stone)), listOf(stone))

        world[BlockPos(3, 100, 4)] = stone
        assertEquals(11, world.chunkAt(ChunkPos(0, 0)).subChunks.size) // Sub-chunk 10 holds y 96 to 111
        assertEquals(stone.networkId, world.blockAt(BlockPos(3, 100, 4)))

        world[BlockPos(3, 100, 4)] = air
        assertEquals(1, world.chunkAt(ChunkPos(0, 0)).subChunks.size)
        assertFailsWith<IllegalArgumentException> { world[BlockPos(0, MAX_Y + 1, 0)] = stone }
    }

    private val air = BlockState(AIR, NbtMap.EMPTY, VERSION)
    private val stone = BlockState("minecraft:stone", NbtMap.EMPTY, VERSION)

    private companion object {
        const val VERSION = 18168865
    }
}
