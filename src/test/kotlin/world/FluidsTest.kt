package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.BlockTrait
import org.koremc.registry.BlockTraits
import org.koremc.registry.LiquidReaction
import org.koremc.registry.Tint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FluidsTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val liquids = listOf(WATER, FLOWING_WATER, LAVA, FLOWING_LAVA).flatMap { name -> (0..15).map { state(name, "liquid_depth" to it) } }
    private val torch = state("minecraft:torch", TORCH_FACING to "top")
    private val fence = state("minecraft:oak_fence")
    private val lever = state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to 0.toByte())
    private val snow = state(SNOW_LAYER, "covered_bit" to 0.toByte(), "height" to 0)
    private val powder = state("minecraft:red_concrete_powder")
    private val concrete = state("minecraft:red_concrete")
    private val palette = BlockPalette(
        listOf(air, stone, torch, fence, lever, snow, powder, concrete, state("minecraft:obsidian"), state("minecraft:cobblestone")) + liquids,
    )

    /** What the game says of the blocks here: torches break and drop in liquid, snow breaks, levers let water in, and fences hold it. */
    private val traits = BlockTraits(
        mapOf(
            torch to BlockTrait(0, Tint.NONE, 0, LiquidReaction.POPPED),
            snow to BlockTrait(0, Tint.NONE, 0, LiquidReaction.BROKEN),
            lever to BlockTrait(0, Tint.NONE, 0, LiquidReaction.NO_REACTION, holdsWater = true),
            fence to BlockTrait(0, Tint.NONE, 0, LiquidReaction.BLOCKING, holdsWater = true),
        ).mapKeys { it.key.networkId },
    )

    // One layer of stone at the bottom of the world, the floor.
    private val world = FlatWorld(palette, listOf(stone), traits = traits)
    private val floor = BlockPos(0, MIN_Y + 1, 0)
    private fun at(x: Int, y: Int = 0, z: Int = 0) = BlockPos(floor.x + x, floor.y + y, floor.z + z)

    private fun water(depth: Int) = world.liquidState(Liquid.WATER, depth)
    private fun lava(depth: Int) = world.liquidState(Liquid.LAVA, depth)
    private fun depthAt(pos: BlockPos) = world.liquidHere(pos)?.depth

    /** Ticks the world until no liquid is due to move, returning what each tick changed. */
    private fun untilStill(): List<Changes> {
        val ticks = mutableListOf<Changes>()
        while (ticks.size < 2000) {
            ticks += world.tickBlocks()
            if (!world.hasScheduledUpdates && world.blockChanges.isEmpty()) break
        }
        return ticks
    }

    @Test
    fun `water from a source spreads seven blocks over a floor, thinner each block, and dries up once the source goes`() {
        world[at(0)] = water(0)
        untilStill()

        assertEquals((0..7).toList(), (0..7).map { depthAt(at(it)) })
        assertEquals(air, world.stateAt(at(8)))
        assertEquals(3, depthAt(at(1, z = 2)), "it spreads every way")

        world[at(0)] = air
        untilStill()
        assertEquals(List(8) { air }, (0..7).map { world.stateAt(at(it)) })
    }

    @Test
    fun `water flows down before it spreads, falling, and spreads afresh where it lands`() {
        world[at(0, y = 3)] = water(0)
        repeat(6) { world.tickBlocks() }
        assertTrue(depthAt(at(0, y = 2))!! >= 8, "down first")
        assertNull(depthAt(at(1, y = 3)), "and not out, while it can go down")
        untilStill()

        assertTrue((0..2).all { depthAt(at(0, y = it))!! >= 8 }, "falling, all the way down")
        assertEquals(1, depthAt(at(1, y = 3)), "a source spreads out too, once there's water under it")
        assertEquals(1, depthAt(at(2)), "spreading afresh where it lands")
    }

    @Test
    fun `water between two sources over a floor is a source too`() {
        world[at(0)] = water(0)
        world[at(2)] = water(0)
        untilStill()

        assertEquals(0, depthAt(at(1)))
        world[at(0)] = air
        untilStill()
        assertEquals(1, depthAt(at(0)), "the new source feeds it now")
    }

    @Test
    fun `water flows toward the nearest way down`() {
        world[at(2, y = -1)] = air // A hole in the floor two blocks east
        world[at(0)] = water(0)
        untilStill()

        // It flows only east, toward the hole, never west.
        assertEquals(1, depthAt(at(1)))
        assertNull(depthAt(at(-1)))
    }

    @Test
    fun `water breaks a torch, which drops, washes snow away, which doesn't, fills a lever, and stops at a fence`() {
        world[at(1)] = torch
        world[at(-1)] = snow
        world[at(0, z = 1)] = lever
        world[at(0, z = -1)] = fence
        world[at(0)] = water(0)
        val ticks = untilStill()

        assertEquals(water(1), world.stateAt(at(1)))
        assertEquals(listOf(torch), ticks.flatMap { it.broken.values }.map { it.state })
        assertEquals(water(1), world.stateAt(at(-1)))
        assertEquals(lever, world.stateAt(at(0, z = 1)))
        assertEquals(1, world.liquidAt(at(0, z = 1))?.liquidDepth, "flowing inside the lever")
        assertEquals(fence, world.stateAt(at(0, z = -1)))
        assertNull(world.liquidAt(at(0, z = -1)))
    }

    @Test
    fun `lava spreads three blocks, thinning twice as fast, and more slowly than water`() {
        world[at(0)] = lava(0)
        val ticks = untilStill()

        assertEquals(listOf(0, 2, 4, 6), (0..3).map { depthAt(at(it)) })
        assertEquals(air, world.stateAt(at(4)))
        assertTrue(ticks.size >= 3 * 30, "a step every 30 ticks")
    }

    @Test
    fun `water touching lava turns a source to obsidian and flowing lava to cobblestone, and lava falling on water makes stone`() {
        world[at(0)] = lava(0)
        world[at(4)] = lava(2)
        world[at(1)] = water(0)
        world[at(5)] = water(0)
        world.tickBlocks()

        assertEquals("minecraft:obsidian", world.stateAt(at(0))?.name)
        assertEquals("minecraft:cobblestone", world.stateAt(at(4))?.name)
        assertTrue(world.tickBlocks().sounds.isEmpty(), "the fizz was last tick")

        world[at(8)] = water(0)
        world[at(8, y = 2)] = lava(0)
        untilStill()
        assertEquals("minecraft:stone", world.stateAt(at(8))?.name, "where the water was")
        assertEquals(Liquid.LAVA, world.liquidHere(at(8, y = 1))?.liquid)
    }

    @Test
    fun `a block that holds water put in a source takes it in, keeps it, and leaves it there when it's broken`() {
        world[at(0)] = water(0)
        world[at(0)] = fence

        assertEquals(fence, world.stateAt(at(0)))
        assertEquals(WATER, world.liquidAt(at(0))?.name, "still, inside a block")
        assertTrue(at(0) in world.liquidChanges)
        untilStill()
        assertEquals(1, depthAt(at(1)), "and it flows out of it")

        world.breakBlock(at(0))
        assertEquals(water(0), world.stateAt(at(0)))
        assertNull(world.liquidAt(at(0)))
        assertTrue(world.breakBlock(at(0)).broken.isEmpty(), "and water doesn't break")
        assertEquals(water(0), world.stateAt(at(0)))

        world[at(0)] = stone
        assertNull(world.liquidAt(at(0)), "a block that doesn't hold water puts it out")
    }

    @Test
    fun `buckets take up sources, not flowing water, and empty into blocks that hold water, or beside what was clicked`() {
        world[at(0)] = water(0)
        untilStill()
        assertNull(world.fillBucket(at(1), Face.UP), "flowing")
        assertEquals(Liquid.WATER to at(0), world.fillBucket(at(0), Face.UP))
        assertEquals(air, world.stateAt(at(0)))

        val broken = linkedMapOf<BlockPos, BrokenBlock>()
        world[at(10)] = fence
        assertEquals(at(10), world.emptyBucket(Liquid.WATER, at(10), Face.UP, broken))
        assertEquals(WATER, world.liquidAt(at(10))?.name)
        assertNull(world.emptyBucket(Liquid.WATER, at(10), Face.UP, broken), "a block with water inside keeps it as it is")
        assertEquals(air, world.stateAt(at(10, y = 1)), "and none goes on top")
        assertEquals(at(11, y = -1).side(Face.UP), world.emptyBucket(Liquid.LAVA, at(11, y = -1), Face.UP, broken))
        assertEquals(lava(0), world.stateAt(at(11)))
        assertNull(world.emptyBucket(Liquid.WATER, at(20, y = -1), Face.DOWN, broken), "no room inside the floor")
    }

    @Test
    fun `an empty bucket clicked on a block holding water under lava takes the water, and the lava the player looked through after`() {
        world[at(0)] = fence
        world.setLiquid(at(0), world.liquidState(Liquid.WATER, 0, inside = true))
        world[at(0, y = 1)] = lava(0)

        assertEquals(Liquid.WATER to at(0), world.fillBucket(at(0), Face.UP))
        assertNull(world.liquidAt(at(0)))
        assertEquals(lava(0), world.stateAt(at(0, y = 1)), "the lava stays, for now")
        assertEquals(Liquid.LAVA to at(0, y = 1), world.fillBucket(at(0), Face.UP))
        assertEquals(air, world.stateAt(at(0, y = 1)))
    }

    @Test
    fun `lava taken up before it moves, and put back, still moves`() {
        world[at(0)] = lava(0)
        world.tickBlocks()
        world[at(0)] = air
        repeat(40) { world.tickBlocks() }
        world[at(0)] = lava(0)
        untilStill()

        assertEquals(2, depthAt(at(1)))
    }

    @Test
    fun `concrete powder hardens touching water, and falling into it`() {
        world[at(1)] = water(0)
        world[at(0)] = powder
        world.tickBlocks()
        assertEquals(concrete, world.stateAt(at(0)))

        world[at(10)] = water(0)
        world[at(10, y = 6)] = powder
        untilStill()
        assertEquals(concrete, world.stateAt(at(10)))
        assertEquals(air, world.stateAt(at(10, y = 1)))
    }
}
