package org.koremc.storage

import org.cloudburstmc.nbt.NbtMap
import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.world.BlockPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelDatTest {
    private fun state(name: String) = BlockState(name, NbtMap.EMPTY, version = 1)

    private val bedrock = state("minecraft:bedrock")
    private val dirt = state("minecraft:dirt")
    private val grass = state("minecraft:grass_block")
    private val blocks = BlockPalette(listOf(state(AIR), bedrock, dirt, grass))
    private val classic = FlatLayers(listOf(bedrock, dirt, dirt, grass))

    private val directory = createTempDirectory("kore-level")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun create() = LevelDat.create("Test", classic, BlockPos(0, -60, 0), GameMode.CREATIVE, Difficulty.EASY, seed = 42, cheats = true, version = "1.26.50", protocol = 2193)

    @Test
    fun `a new world's settings are the game's defaults, with the flat layers, spawn and versions Kore says, and read back as written`() {
        val file = directory.resolve("level.dat")
        create().write(file)

        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10, bytes.getInt(0))
        assertEquals(file.readBytes().size - 8, bytes.getInt(4))

        val read = LevelDat.read(file)
        assertEquals("Test", read.name)
        assertEquals(FLAT_GENERATOR, read.generator)
        assertEquals(42L, read.seed)
        assertEquals(BlockPos(0, -60, 0), read.spawn)
        assertEquals(classic, read.flatLayers(blocks))
        assertEquals(1, read.data.getInt("GameType"))
        assertEquals(1, read.data.getInt("Difficulty"))
        assertEquals(listOf(1, 26, 50, 0, 0), read.data.getList("lastOpenedWithVersion", org.cloudburstmc.nbt.NbtType.INT))
        assertTrue(read.data.getBoolean("dodaylightcycle"))
    }

    @Test
    fun `flat layers name runs of blocks from the bottom up, the old grass still grass blocks, and a preset or an unknown block isn't read`() {
        fun layers(json: String) = LevelDat(NbtMap.builder().putString("FlatWorldLayers", json).build()).flatLayers(blocks)
        assertEquals(
            FlatLayers(listOf(bedrock, dirt, dirt, grass), biome = 4),
            layers("""{"biome_id":4,"block_layers":[{"block_name":"minecraft:bedrock","count":1},{"block_name":"minecraft:dirt","count":2},{"block_name":"minecraft:grass","count":1}],"encoding_version":6}"""),
        )
        assertNull(layers("""{"biome_id":1,"encoding_version":6,"preset_id":"TheVoid","world_version":"version.post_1_18"}"""))
        assertNull(layers("""{"block_layers":[{"block_name":"minecraft:cheese","count":1}]}"""))
        assertNull(layers("not json"))
        assertEquals(
            """{"biome_id":1,"block_layers":[{"block_name":"minecraft:bedrock","count":1},{"block_name":"minecraft:dirt","count":2},{"block_name":"minecraft:grass_block","count":1}],"encoding_version":6,"structure_options":null,"world_version":"version.post_1_18"}""",
            flatLayersJson(classic),
        )
    }

    @Test
    fun `updating the settings changes only what Kore has a say in, keeping the rest as the game left them`() {
        val saved = create().with { putString("customSetting", "kept").putInt("Time", 6000) }
        val updated = saved.updated(BlockPos(8, -50, 8), GameMode.SURVIVAL, Difficulty.HARD, cheats = false, version = "1.26.60", protocol = 2200)
        assertEquals("kept", updated.data.getString("customSetting"))
        assertEquals(6000, updated.data.getInt("Time"))
        assertEquals(BlockPos(8, -50, 8), updated.spawn)
        assertEquals(listOf(0, 3, 2200), listOf(updated.data.getInt("GameType"), updated.data.getInt("Difficulty"), updated.data.getInt("NetworkVersion")))

        // A spawn the game puts on the ground has no height of its own.
        assertNull(saved.with { putInt("SpawnY", Short.MAX_VALUE.toInt()) }.spawn)
    }
}
