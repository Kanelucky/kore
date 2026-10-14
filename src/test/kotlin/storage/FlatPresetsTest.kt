package org.koremc.storage

import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import org.koremc.registry.AIR
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import org.koremc.world.BlockPos
import org.koremc.world.MIN_Y
import org.koremc.world.PLAINS_BIOME_ID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The game's flat world presets, made of the game's own blocks. */
class FlatPresetsTest {
    private val blocks get() = data.blocks
    private val directory = createTempDirectory("kore-presets")
    private val settings = WorldSettings(GameMode.CREATIVE, Difficulty.NORMAL, cheats = true, seed = 7, version = "1.26.50", protocol = 2193)

    @BeforeTest
    fun needGameData() = assumeGameData()

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun names(preset: FlatPreset) = preset.layers(blocks).layers.map { it.name.removePrefix("minecraft:") }

    @Test
    fun `presets go by the game's names for them, exactly, and Tunneller's Dream by its American spelling too`() {
        for (preset in FlatPreset.entries) assertEquals(preset, FlatPreset.named(preset.title))
        assertEquals(
            listOf("Classic Flat", "Tunneller's Dream", "Water World", "Overworld", "Snowy Kingdom", "Bottomless Pit", "Desert", "Redstone Ready", "The Void"),
            FlatPreset.entries.map { it.title },
        )
        assertEquals(FlatPreset.TUNNELLERS_DREAM, FlatPreset.named("Tunneler's Dream"))
        assertNull(FlatPreset.named("classic flat"))
        assertNull(FlatPreset.named("Superflat"))
    }

    @Test
    fun `each preset's layers are the game's, from the bottom of the world up, in its biome`() {
        assertEquals(listOf("bedrock", "dirt", "dirt", "grass_block"), names(FlatPreset.CLASSIC_FLAT))
        assertEquals(237, names(FlatPreset.TUNNELLERS_DREAM).size)
        assertEquals(listOf(1, 64, 5, 5, 5, 90), runs(FlatPreset.WATER_WORLD))
        assertEquals("deepslate", names(FlatPreset.WATER_WORLD)[1])
        assertEquals("water", names(FlatPreset.WATER_WORLD).last())
        assertEquals(64, names(FlatPreset.OVERWORLD).size)
        assertEquals(listOf("grass_block", "snow_layer"), names(FlatPreset.SNOWY_KINGDOM).takeLast(2))
        assertEquals(listOf("cobblestone", "cobblestone", "dirt", "dirt", "dirt", "grass_block"), names(FlatPreset.BOTTOMLESS_PIT))
        assertEquals(listOf(1, 3, 52, 8), runs(FlatPreset.DESERT))
        assertEquals(listOf(1, 3, 116), runs(FlatPreset.REDSTONE_READY))
        assertEquals(listOf(AIR), FlatPreset.THE_VOID.layers(blocks).layers.map { it.name })

        val biomes = FlatPreset.entries.associateWith { BIOME_NAMES[it.layers(blocks).biome] }
        assertEquals("minecraft:extreme_hills", biomes[FlatPreset.TUNNELLERS_DREAM])
        assertEquals("minecraft:deep_ocean", biomes[FlatPreset.WATER_WORLD])
        assertEquals("minecraft:ice_plains", biomes[FlatPreset.SNOWY_KINGDOM])
        assertEquals("minecraft:desert", biomes[FlatPreset.DESERT])
        assertEquals(PLAINS_BIOME_ID, FlatPreset.THE_VOID.layers(blocks).biome)
        for (biome in biomes.values) assertTrue(data.biomes.any { it.name == biome }, "the game has no $biome")
    }

    /** How deep each run of the same block in [preset] is, from the bottom up. */
    private fun runs(preset: FlatPreset) = names(preset).fold(mutableListOf<Pair<String, Int>>()) { runs, name ->
        runs.apply { if (lastOrNull()?.first == name) set(lastIndex, name to last().second + 1) else add(name to 1) }
    }.map { it.second }

    @Test
    fun `a new world of The Void is a stone platform at the bottom of the world, with cobblestone in the middle, which players start on, kept as it's saved`() {
        val items = ItemStacks(data.items, data.creative, blocks)
        fun open() = WorldStorage.open(directory, "Void", FlatPreset.THE_VOID.layers(blocks), blocks, items, settings)

        open().apply {
            assertEquals(BlockPos(0, MIN_Y + 1, 0), world.spawn)
            assertEquals("minecraft:cobblestone", world.stateAt(BlockPos(0, MIN_Y, 0))?.name)
            for (corner in listOf(BlockPos(16, MIN_Y, 16), BlockPos(-16, MIN_Y, -16), BlockPos(16, MIN_Y, -16))) {
                assertEquals("minecraft:stone", world.stateAt(corner)?.name, "at $corner")
            }
            assertEquals(AIR, world.stateAt(BlockPos(17, MIN_Y, 0))?.name)
            assertEquals(AIR, world.stateAt(BlockPos(0, MIN_Y + 1, 0))?.name)
            close()
        }
        open().apply {
            assertEquals("minecraft:cobblestone", world.stateAt(BlockPos(0, MIN_Y, 0))?.name)
            assertEquals("minecraft:stone", world.stateAt(BlockPos(-16, MIN_Y, 16))?.name)
            assertEquals(AIR, world.stateAt(BlockPos(100, MIN_Y, 100))?.name)
            close()
        }
    }

    /** How many layers deep the snow on Snowy Kingdom's grass lies in each column of [area], by [world]. */
    private fun snowIn(world: org.koremc.world.FlatWorld, area: IntRange = 0 until 64) = area.flatMap { x ->
        area.map { z -> world.stateAt(BlockPos(x, 0, z))?.takeIf { it.name == "minecraft:snow_layer" }?.let { (it.properties["height"] as Int) + 1 } ?: 0 }
    }

    @Test
    fun `Snowy Kingdom's snow lies unevenly, mostly a layer deep, deeper in drifts and bare in patches, the same each time it opens`() {
        val items = ItemStacks(data.items, data.creative, blocks)
        fun open(seed: Long = 7) = WorldStorage.open(directory, "Snowy", FlatPreset.SNOWY_KINGDOM.layers(blocks), blocks, items, settings.copy(seed = seed), biomes = data.biomes)

        val snow = open().run { snowIn(world).also { close() } }
        val counts = snow.groupingBy { it }.eachCount()
        assertEquals(setOf(0, 1, 2), counts.keys)
        assertTrue(counts.getValue(1) > counts.getValue(2) && counts.getValue(2) > counts.getValue(0), "$counts")
        open().run {
            assertEquals(snow, snowIn(world), "laid out by the world's seed")
            assertEquals("minecraft:grass_block", world.stateAt(BlockPos(5, -1, 5))?.name)
            close()
        }
        directory.toFile().deleteRecursively()
        open(seed = 8).run {
            assertTrue(snowIn(world) != snow, "another seed, other drifts")
            close()
        }
    }

    @Test
    fun `presets without snow on top are the same in every column`() {
        val items = ItemStacks(data.items, data.creative, blocks)
        WorldStorage.open(directory, "Overworld", FlatPreset.OVERWORLD.layers(blocks), blocks, items, settings, biomes = data.biomes).run {
            val tops = (0 until 40).flatMap { x -> (0 until 40).map { z -> world.stateAt(BlockPos(x, MIN_Y + 63, z))?.name } }.toSet()
            assertEquals(setOf("minecraft:grass_block"), tops)
            close()
        }
    }

    @Test
    fun `a world made from a preset opens again in its biome, whatever preset is asked for then`() {
        val items = ItemStacks(data.items, data.creative, blocks)
        WorldStorage.open(directory, "Desert", FlatPreset.DESERT.layers(blocks), blocks, items, settings).close()
        WorldStorage.open(directory, "Desert", FlatPreset.CLASSIC_FLAT.layers(blocks), blocks, items, settings).apply {
            assertEquals(DESERT_BIOME_ID, world.biome)
            assertEquals("minecraft:sand", world.stateAt(BlockPos(40, MIN_Y + 63, 40))?.name)
            close()
        }
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
