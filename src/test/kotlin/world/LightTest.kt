package org.koremc.world

import org.koremc.registry.BlockState
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The light in the world, by the game's own blocks and what it says of how they give off and stop light. */
class LightTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")), traits = data.traits)
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    /** Shuts the space at [pos] in with stone on every side but the top, which gets [roof]. */
    private fun shutIn(pos: BlockPos, roof: String) {
        for (side in AROUND) world[pos.side(side)] = block("minecraft:stone")
        world[pos.side(Face.UP)] = block(roof)
    }

    @Test
    fun `the sky's light falls whole on what's open to it, spreads a level less at a time under a roof, and reaches no room shut in`() {
        assertEquals(15, world.skyLightAt(above))
        world[above.side(Face.UP).side(Face.UP)] = block("minecraft:stone")
        assertEquals(14, world.skyLightAt(above))
        shutIn(above, "minecraft:stone")
        assertEquals(0, world.skyLightAt(above))
    }

    @Test
    fun `light falls through glass whole, but dims through leaves`() {
        shutIn(above, "minecraft:glass")
        assertEquals(15, world.skyLightAt(above))
        shutIn(above, "minecraft:oak_leaves")
        assertEquals(13, world.skyLightAt(above)) // A level lost going into the leaves, and another coming out of them
    }

    @Test
    fun `a torch lights the blocks around it a level less for each step, the long way round a block in the way`() {
        world[above] = block("minecraft:torch")
        assertEquals(14, world.blockLightAt(above))
        assertEquals(11, world.blockLightAt(BlockPos(3, above.y, 0)))
        assertEquals(12, world.blockLightAt(BlockPos(2, above.y, 0)))
        world[BlockPos(1, above.y, 0)] = block("minecraft:stone")
        assertEquals(10, world.blockLightAt(BlockPos(2, above.y, 0))) // Over the stone
        assertEquals(0, world.blockLightAt(BlockPos(20, above.y, 0)))
    }

    @Test
    fun `what grows reckons the sky's light darker at night and in rain, but not a torch's`() {
        world.time = 6000
        assertEquals(15, world.internalLightAt(above))
        world.weather.raining = true
        assertEquals(12, world.internalLightAt(above))
        world.weather.raining = false
        world.time = 18000
        assertEquals(4, world.internalLightAt(above))
        assertEquals(15, world.lightAt(above)) // The light itself is the same, night or day
        world[BlockPos(1, above.y, 0)] = block("minecraft:torch")
        assertEquals(13, world.internalLightAt(above))
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
