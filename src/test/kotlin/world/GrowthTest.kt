package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What random ticks grow, spread, dry, decay, melt and weather, by the game's own blocks. */
class GrowthTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)
    private val twoUp = above.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")), traits = data.traits)
        world.time = 6000 // Noon
    }

    private fun block(name: String, vararg properties: Pair<String, Any>): BlockState {
        val default = blocks.defaultOf(name) ?: error("no block $name")
        return if (properties.isEmpty()) default else blocks.stateOf(name, default.properties + properties)
    }

    private fun put(pos: BlockPos, name: String, vararg properties: Pair<String, Any>) {
        world[pos] = block(name, *properties)
    }

    private fun nameAt(pos: BlockPos) = world.stateAt(pos)?.name

    private fun numberAt(pos: BlockPos, property: String) = world.stateAt(pos)?.properties?.get(property)

    /** Gives the block at [pos] [times] random ticks, picking it each time, with chance from [seed]. Returns what they did. */
    private fun tick(pos: BlockPos, times: Int = 1, seed: Int = 1): Growing {
        val growing = Growing(Random(seed))
        repeat(times) { world.stateAt(pos)?.let { world.randomTick(pos, it, growing) } }
        return growing
    }

    private val water get() = world.liquidState(Liquid.WATER, 0)

    @Test
    fun `crops on wet farmland grow a stage now and then in daylight, to harvest, but not in the dark of night`() {
        put(above, "minecraft:farmland", "moisturized_amount" to 7)
        put(twoUp, "minecraft:wheat")
        world.time = 18000
        tick(twoUp, 200)
        assertEquals(0, numberAt(twoUp, GROWTH))
        world.time = 6000
        tick(twoUp, 300)
        assertEquals(MAX_CROP_GROWTH, numberAt(twoUp, GROWTH))
    }

    @Test
    fun `farmland dries a step a tick without water, and goes back to dirt once dry, unless something grows on it, and water four off wets it`() {
        put(above, "minecraft:farmland", "moisturized_amount" to 7)
        tick(above, 7)
        assertEquals(0, numberAt(above, "moisturized_amount"))
        put(twoUp, "minecraft:wheat")
        tick(above)
        assertEquals("minecraft:farmland", nameAt(above))
        world[twoUp] = blocks.stateOf(AIR)
        tick(above)
        assertEquals("minecraft:dirt", nameAt(above))

        put(above, "minecraft:farmland")
        world[BlockPos(4, above.y, 0)] = water
        tick(above)
        assertEquals(7, numberAt(above, "moisturized_amount"))
    }

    @Test
    fun `a crop breaks once its farmland goes back to dirt, and a block on farmland turns it to dirt`() {
        put(above, "minecraft:farmland")
        put(twoUp, "minecraft:carrots")
        put(above, "minecraft:dirt")
        world.settle(listOf(above))
        assertEquals("minecraft:carrots", world.tickBlocks().broken[twoUp]?.state?.name)

        val farmland = BlockPos(5, above.y, 0)
        put(farmland, "minecraft:farmland")
        put(farmland.side(Face.UP), "minecraft:pumpkin")
        world.settle(listOf(farmland.side(Face.UP)))
        world.tickBlocks()
        assertEquals("minecraft:dirt", nameAt(farmland))
    }

    @Test
    fun `nothing that stands on the ground counts as a whole block, so planting on farmland keeps it farmland`() {
        // Snow aside, which Kore takes as whole for what lands on it.
        val whole = blocks.states.filter { it.heldFrom == Face.DOWN && it.isFullCube && it.name != SNOW_LAYER }.map { it.name }.distinct()
        assertEquals(emptyList(), whole)

        for ((x, crop) in (CROPS + STEM_FRUIT.keys + setOf(TORCHFLOWER_CROP, PITCHER_CROP)).withIndex()) {
            val farmland = BlockPos(x * 2, above.y, 0)
            put(farmland, "minecraft:farmland")
            put(farmland.side(Face.UP), crop)
            world.settle(listOf(farmland.side(Face.UP)))
        }
        world.tickBlocks()
        assertTrue((0 until 7).all { nameAt(BlockPos(it * 2, above.y, 0)) == "minecraft:farmland" })
    }

    @Test
    fun `falling onto farmland tramples it, likelier the further the fall, and never from half a block`() {
        put(above, "minecraft:farmland")
        assertTrue((1..100).none { world.landOn(above, 0.5f, Random(it)) })
        assertEquals("minecraft:farmland", nameAt(above))
        val tries = (1..1000).count { put(above, "minecraft:farmland"); world.landOn(above, 1f, Random(it)) }
        assertTrue(tries in 400..600, "$tries") // Half the time from a block up
        put(above, "minecraft:farmland")
        assertTrue(world.landOn(above, 1.5f))
        assertEquals("minecraft:dirt", nameAt(above))
        assertFalse(world.landOn(ground, 10f)) // Stone takes it
    }

    @Test
    fun `sugar cane by water grows to three tall, and breaks once its water goes`() {
        put(above, "minecraft:sand")
        val pool = BlockPos(1, above.y, 0)
        world[pool] = water
        put(twoUp, "minecraft:reeds")
        repeat(100) {
            var top = twoUp
            while (nameAt(top.side(Face.UP)) == REEDS) top = top.side(Face.UP)
            tick(top, seed = it)
        }
        assertEquals(listOf(REEDS, REEDS, REEDS, AIR), (0..3).map { nameAt(BlockPos(0, twoUp.y + it, 0)) })

        world[pool] = blocks.stateOf(AIR)
        assertTrue(twoUp in tick(twoUp).broken)
    }

    @Test
    fun `a grown stem grows its fruit on the dirt beside it, and bends towards it`() {
        put(above, "minecraft:farmland", "moisturized_amount" to 7)
        for (side in AROUND) put(above.side(side), "minecraft:dirt")
        put(twoUp, MELON_STEM, GROWTH to 7)
        var growing = tick(twoUp)
        for (seed in 2..500) {
            if (AROUND.any { nameAt(twoUp.side(it)) == MELON }) break
            growing = tick(twoUp, seed = seed)
        }
        val fruit = AROUND.single { nameAt(twoUp.side(it)) == MELON }
        world.settle(growing.changed)
        assertEquals(fruit.ordinal, numberAt(twoUp, FACING_DIRECTION))
        // Once grown, one fruit at a time.
        tick(twoUp, 500)
        assertEquals(1, AROUND.count { nameAt(twoUp.side(it)) == MELON })
    }

    @Test
    fun `grass spreads to dirt in daylight, but not at night, and dies under a block`() {
        val grass = BlockPos(1, above.y, 0)
        put(above, "minecraft:dirt")
        put(grass, "minecraft:grass_block")
        world.time = 18000
        tick(grass, 300)
        assertEquals("minecraft:dirt", nameAt(above))
        world.time = 6000
        tick(grass, 300)
        assertEquals("minecraft:grass_block", nameAt(above))

        put(above.side(Face.UP), "minecraft:stone")
        tick(above)
        assertEquals("minecraft:dirt", nameAt(above))
    }

    @Test
    fun `leaves decay once their wood is gone, but not those a player placed, nor those with wood within seven`() {
        put(above, "minecraft:oak_log")
        put(twoUp, "minecraft:oak_leaves")
        world[above] = blocks.stateOf(AIR)
        world.settle(listOf(above))
        assertEquals(1.toByte(), world.stateAt(twoUp)?.properties?.get(LEAF_UPDATE)) // Woken to look for its wood
        assertTrue(twoUp in tick(twoUp).broken)

        put(twoUp, "minecraft:oak_leaves", PERSISTENT to 1.toByte())
        world.settle(listOf(above))
        tick(twoUp)
        assertEquals("minecraft:oak_leaves", nameAt(twoUp))

        // A row of leaves from a log: the seventh along stays, and stops looking, but the eighth decays.
        val log = BlockPos(10, above.y, 0)
        put(log, "minecraft:oak_log")
        for (step in 1..8) put(log.side(Face.EAST, step), "minecraft:oak_leaves", LEAF_UPDATE to 1.toByte())
        tick(log.side(Face.EAST, 7))
        assertEquals(0.toByte(), world.stateAt(log.side(Face.EAST, 7))?.properties?.get(LEAF_UPDATE))
        assertTrue(log.side(Face.EAST, 8) in tick(log.side(Face.EAST, 8)).broken)
    }

    @Test
    fun `ice melts beside a torch, but not in the sun, and snow melts a layer at a time`() {
        put(above, "minecraft:ice")
        tick(above, 10)
        assertEquals("minecraft:ice", nameAt(above))
        put(BlockPos(1, above.y, 0), "minecraft:torch")
        tick(above)
        assertEquals(Liquid.WATER, world.stateAt(above)?.liquid)

        val snow = BlockPos(0, above.y, 1)
        put(snow, SNOW_LAYER, "height" to 1)
        tick(snow)
        assertEquals(0, numberAt(snow, "height"))
        assertTrue(snow in tick(snow).broken)
    }

    @Test
    fun `copper weathers a stage now and then, but not while less weathered copper is near`() {
        put(above, "minecraft:copper_block")
        tick(above, 500)
        assertNotEquals("minecraft:copper_block", nameAt(above))

        put(above, "minecraft:exposed_copper")
        put(BlockPos(2, above.y, 0), "minecraft:copper_block")
        tick(above, 500)
        assertEquals("minecraft:exposed_copper", nameAt(above))
    }

    @Test
    fun `budding amethyst grows buds on its sides, which grow into clusters`() {
        put(twoUp, "minecraft:budding_amethyst")
        tick(twoUp, 2000)
        assertTrue(Face.entries.any { nameAt(twoUp.side(it)) == "minecraft:amethyst_cluster" })
        val cluster = Face.entries.first { nameAt(twoUp.side(it)) == "minecraft:amethyst_cluster" }
        assertEquals(cluster.id, world.stateAt(twoUp.side(cluster))?.properties?.get("minecraft:block_face"))
    }

    @Test
    fun `a stalactite under water drips into the cauldron below it`() {
        put(above, "minecraft:cauldron")
        val root = BlockPos(0, above.y + 3, 0)
        put(root, POINTED_DRIPSTONE, HANGING to 1.toByte())
        put(root.side(Face.UP), "minecraft:dripstone_block")
        world[root.side(Face.UP).side(Face.UP)] = water
        tick(root, 200)
        assertTrue(numberAt(above, "fill_level") as Int > 0)
    }

    @Test
    fun `random ticks land on blocks near players, as fast as the game rule says, and none with nobody near`() {
        put(above, "minecraft:farmland", "moisturized_amount" to 7)
        put(twoUp, "minecraft:wheat")
        world.rules.set("randomTickSpeed", 4096)
        assertTrue(world.tickRandomly(emptyList()).isEmpty)
        repeat(20) { world.tickRandomly(listOf(ChunkPos(0, 0)), Random(it)) }
        assertTrue(numberAt(twoUp, GROWTH) as Int > 0)

        world.rules.set("randomTickSpeed", 0)
        put(twoUp, "minecraft:wheat")
        repeat(20) { world.tickRandomly(listOf(ChunkPos(0, 0)), Random(it)) }
        assertEquals(0, numberAt(twoUp, GROWTH))
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
