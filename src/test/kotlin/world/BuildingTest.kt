package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingTest {
    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val shortGrass = state("minecraft:short_grass")
    private val slab = state("minecraft:oak_slab", "minecraft:vertical_half" to "bottom")
    private val stairs = state("minecraft:oak_stairs", "upside_down_bit" to 0.toByte(), "weirdo_direction" to 0, "minecraft:corner" to "none")
    private val log = state("minecraft:oak_log", "pillar_axis" to "y")
    private val torch = state("minecraft:torch", "torch_facing_direction" to "unknown")
    private val furnace = state("minecraft:furnace", "minecraft:cardinal_direction" to "south")
    private val terracotta = state("minecraft:white_glazed_terracotta", "facing_direction" to 0)
    private val button = state("minecraft:stone_button", "button_pressed_bit" to 0.toByte(), "facing_direction" to 0)
    private val sunflower = state("minecraft:sunflower", "upper_block_bit" to 0.toByte())
    private val ladder = state("minecraft:ladder", "facing_direction" to 0)
    private val lever = lever("down_east_west")

    private val palette = BlockPalette(
        listOf(air, stone, shortGrass) +
            listOf("bottom", "top").flatMap { half ->
                listOf("minecraft:oak_slab", "minecraft:oak_double_slab").map { state(it, "minecraft:vertical_half" to half) }
            } +
            (0..1).flatMap { upsideDown ->
                (0..3).map { direction -> state("minecraft:oak_stairs", "upside_down_bit" to upsideDown.toByte(), "weirdo_direction" to direction, "minecraft:corner" to "none") }
            } +
            listOf("x", "y", "z").map { state("minecraft:oak_log", "pillar_axis" to it) } +
            listOf("unknown", "top", "north", "south", "west", "east").map { state("minecraft:torch", "torch_facing_direction" to it) } +
            listOf("north", "south", "west", "east").map { state("minecraft:furnace", "minecraft:cardinal_direction" to it) } +
            (0..5).map { state("minecraft:white_glazed_terracotta", "facing_direction" to it) } +
            (0..5).map { state("minecraft:stone_button", "button_pressed_bit" to 0.toByte(), "facing_direction" to it) } +
            (0..1).map { state("minecraft:sunflower", "upper_block_bit" to it.toByte()) } +
            (2..5).map { state("minecraft:ladder", "facing_direction" to it) } +
            listOf("down_east_west", "down_north_south", "up_east_west", "up_north_south", "north", "south", "west", "east").map { lever(it) },
    )

    // One layer of stone, at the bottom of the world. A stone pillar stands on it, to click the sides of.
    private val world = FlatWorld(palette, listOf(stone)).also { it[PILLAR] = stone }

    @Test
    fun `a block goes in the space beyond the face clicked, if there's room`() {
        assertEquals(listOf(ABOVE_GROUND), world.place(stone, GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH)))
        assertEquals(stone, world.stateAt(ABOVE_GROUND))

        // The ground's side faces more ground.
        assertEquals(emptyList(), world.place(stone, GROUND, Face.EAST, clickY = 0.5f, Placer(Face.SOUTH)))
        world[BlockPos(0, MAX_Y, 0)] = stone
        assertEquals(emptyList(), world.place(stone, BlockPos(0, MAX_Y, 0), Face.UP, clickY = 0f, Placer(Face.SOUTH)))
    }

    @Test
    fun `a block like short grass is replaced, as though the new block went on top of it`() {
        world[ABOVE_GROUND] = shortGrass

        assertEquals(listOf(ABOVE_GROUND), world.place(torch, ABOVE_GROUND, Face.NORTH, clickY = 0.5f, Placer(Face.SOUTH)))
        assertEquals("top", world.stateAt(ABOVE_GROUND)?.properties?.get("torch_facing_direction"))
    }

    @Test
    fun `slabs take the half of the space clicked, and two make a double slab`() {
        world.place(slab, GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH))
        assertEquals(slab, world.stateAt(ABOVE_GROUND))

        // The top of a bottom slab is its open half.
        assertEquals(listOf(ABOVE_GROUND), world.place(slab, ABOVE_GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH)))
        assertEquals(state("minecraft:oak_double_slab", "minecraft:vertical_half" to "bottom"), world.stateAt(ABOVE_GROUND))

        // High on the pillar's side, the slab goes in the top half. Clicked there again, it fills the space.
        world.place(slab, PILLAR, Face.EAST, clickY = 0.75f, Placer(Face.WEST))
        assertEquals(state("minecraft:oak_slab", "minecraft:vertical_half" to "top"), world.stateAt(PILLAR.side(Face.EAST)))
        assertEquals(listOf(PILLAR.side(Face.EAST)), world.place(slab, PILLAR, Face.EAST, clickY = 0.75f, Placer(Face.WEST)))
        assertEquals(state("minecraft:oak_double_slab", "minecraft:vertical_half" to "top"), world.stateAt(PILLAR.side(Face.EAST)))
    }

    @Test
    fun `stairs climb the way the player faces, and turn over when placed high`() {
        world.place(stairs, GROUND, Face.UP, clickY = 0f, Placer(Face.NORTH))
        assertEquals(stairs(upsideDown = 0, direction = 3), world.stateAt(ABOVE_GROUND))

        world.place(stairs, PILLAR, Face.EAST, clickY = 0.75f, Placer(Face.EAST))
        assertEquals(stairs(upsideDown = 1, direction = 0), world.stateAt(PILLAR.side(Face.EAST)))

        world.place(stairs, PILLAR, Face.WEST, clickY = 0.25f, Placer(Face.WEST))
        assertEquals(stairs(upsideDown = 0, direction = 1), world.stateAt(PILLAR.side(Face.WEST)))
    }

    @Test
    fun `pillars lie along the axis of the face clicked`() {
        world.place(log, PILLAR, Face.EAST, clickY = 0.5f, Placer(Face.SOUTH))
        world.place(log, PILLAR, Face.NORTH, clickY = 0.5f, Placer(Face.SOUTH))
        world.place(log, PILLAR, Face.UP, clickY = 0f, Placer(Face.SOUTH))

        assertEquals(listOf("x", "z", "y"), listOf(Face.EAST, Face.NORTH, Face.UP).map { world.stateAt(PILLAR.side(it))?.properties?.get("pillar_axis") })
    }

    @Test
    fun `fronts turn to the player, and attachments point out of the face clicked`() {
        world.place(furnace, PILLAR, Face.UP, clickY = 0f, Placer(Face.SOUTH))
        world.place(terracotta, PILLAR, Face.NORTH, clickY = 0.5f, Placer(Face.EAST))
        world.place(button, PILLAR, Face.EAST, clickY = 0.5f, Placer(Face.SOUTH))
        world.place(torch, PILLAR, Face.WEST, clickY = 0.5f, Placer(Face.SOUTH))

        assertEquals("north", world.stateAt(PILLAR.side(Face.UP))?.properties?.get("minecraft:cardinal_direction"))
        assertEquals(Face.WEST.ordinal, world.stateAt(PILLAR.side(Face.NORTH))?.properties?.get("facing_direction"))
        assertEquals(Face.EAST.ordinal, world.stateAt(PILLAR.side(Face.EAST))?.properties?.get("facing_direction"))
        assertEquals("east", world.stateAt(PILLAR.side(Face.WEST))?.properties?.get("torch_facing_direction")) // Leaning on the pillar, to its east
    }

    @Test
    fun `a block whose rules make a state the game doesn't have keeps the item's state`() {
        // Ladders only go on the sides of blocks.
        assertEquals(ladder, palette.placedState(ladder, ABOVE_GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH)))
    }

    @Test
    fun `a torch placed against what can't hold it stands on the floor, or leans on a neighbour that can`() {
        world.place(torch, GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH))
        val inFront = ABOVE_GROUND.side(Face.NORTH)

        // Against the side of the standing torch, the new torch stands on the floor instead.
        assertEquals(listOf(inFront), world.place(torch, ABOVE_GROUND, Face.NORTH, clickY = 0.5f, Placer(Face.SOUTH)))
        assertEquals(torch("top"), world.stateAt(inFront))

        // Against that one, with stone to the west of the space, it leans on the stone.
        world[inFront.side(Face.NORTH).side(Face.WEST)] = stone
        world.place(torch, inFront, Face.NORTH, clickY = 0.5f, Placer(Face.SOUTH))
        assertEquals(torch("west"), world.stateAt(inFront.side(Face.NORTH)))
    }

    @Test
    fun `a torch with nothing to hold it up isn't placed`() {
        val floating = BlockPos(0, -50, 0)
        world[floating] = stone

        // Under the floating stone, with air all around and below.
        assertEquals(emptyList(), world.place(torch, floating, Face.DOWN, clickY = 0f, Placer(Face.SOUTH)))
        assertEquals(air, world.stateAt(floating.side(Face.DOWN)))
    }

    @Test
    fun `torches, buttons and levers fall with the block holding them up, and no others`() {
        world.place(torch, PILLAR, Face.EAST, clickY = 0.5f, Placer(Face.SOUTH))
        world.place(torch, PILLAR, Face.UP, clickY = 0f, Placer(Face.SOUTH))
        world.place(button, PILLAR, Face.SOUTH, clickY = 0.5f, Placer(Face.NORTH))
        world.place(lever, PILLAR, Face.WEST, clickY = 0.5f, Placer(Face.EAST))
        val beside = PILLAR.side(Face.NORTH)
        world.place(torch, beside.side(Face.DOWN), Face.UP, clickY = 0f, Placer(Face.SOUTH)) // Standing on the ground beside the pillar

        val broken = world.breakBlock(PILLAR).broken.mapValues { it.value.state }

        val fixed = mapOf(
            PILLAR.side(Face.EAST) to torch("west"),
            PILLAR.side(Face.UP) to torch("top"),
            PILLAR.side(Face.SOUTH) to button(Face.SOUTH),
            PILLAR.side(Face.WEST) to lever("west"),
        )
        assertEquals(mapOf(PILLAR to stone) + fixed, broken)
        assertEquals(torch("top"), world.stateAt(beside))
    }

    @Test
    fun `the back of stairs holds torches, buttons and levers, and the front doesn't`() {
        // Three stairs that climb east, so their backs face east.
        val steps = listOf(8, 10, 12).map { z -> BlockPos(8, MIN_Y + 1, z).also { world[it] = stairs(upsideDown = 0, direction = 0) } }

        world.place(torch, steps[0], Face.EAST, clickY = 0.5f, Placer(Face.WEST))
        world.place(button, steps[1], Face.EAST, clickY = 0.5f, Placer(Face.WEST))
        world.place(lever, steps[2], Face.EAST, clickY = 0.5f, Placer(Face.WEST))
        assertEquals(torch("west"), world.stateAt(steps[0].side(Face.EAST)))
        assertEquals(button(Face.EAST), world.stateAt(steps[1].side(Face.EAST)))
        assertEquals(lever("east"), world.stateAt(steps[2].side(Face.EAST)))

        // Against the front, a button has nothing to hold it, and a torch stands on the floor instead.
        assertEquals(emptyList(), world.place(button, steps[1], Face.WEST, clickY = 0.5f, Placer(Face.EAST)))
        world.place(torch, steps[0], Face.WEST, clickY = 0.5f, Placer(Face.EAST))
        assertEquals(torch("top"), world.stateAt(steps[0].side(Face.WEST)))
    }

    @Test
    fun `levers on floors and ceilings lie the way the player faces`() {
        world.place(lever, GROUND, Face.UP, clickY = 0f, Placer(Face.EAST))
        assertEquals(lever("up_east_west"), world.stateAt(ABOVE_GROUND))

        world.place(lever, PILLAR, Face.UP, clickY = 0f, Placer(Face.NORTH))
        assertEquals(lever("up_north_south"), world.stateAt(PILLAR.side(Face.UP)))

        val ceiling = BlockPos(0, -50, 0)
        world[ceiling] = stone
        world.place(lever, ceiling, Face.DOWN, clickY = 0f, Placer(Face.SOUTH))
        assertEquals(lever("down_north_south"), world.stateAt(ceiling.side(Face.DOWN)))
    }

    @Test
    fun `slabs, stairs, torches and plants aren't whole cubes, but some of their faces are`() {
        assertTrue(stone.isFullCube)
        assertTrue(state("minecraft:oak_double_slab", "minecraft:vertical_half" to "bottom").isFullCube)
        assertTrue(state("minecraft:sea_lantern").isFullCube)
        for (partial in listOf(slab, stairs, torch, shortGrass, air, state("minecraft:oak_fence"), state("minecraft:poppy"), state("minecraft:chest"))) {
            assertTrue(!partial.isFullCube, "${partial.name} isn't a whole cube")
        }

        // A slab's whole face is on the side it fills.
        assertTrue(state("minecraft:oak_slab", "minecraft:vertical_half" to "top").hasFullFace(Face.UP))
        assertTrue(slab.hasFullFace(Face.DOWN))
        assertTrue(!slab.hasFullFace(Face.UP))
        assertTrue(!slab.hasFullFace(Face.EAST))

        // Stairs have whole backs and bottoms, or tops when upside down.
        assertEquals(listOf(Face.DOWN, Face.NORTH), Face.entries.filter(stairs(upsideDown = 0, direction = 3)::hasFullFace))
        assertEquals(listOf(Face.UP, Face.WEST), Face.entries.filter(stairs(upsideDown = 1, direction = 1)::hasFullFace))

        assertTrue(state("minecraft:oak_fence").holdsTorchOnTop)
        assertTrue(!state("minecraft:oak_fence").hasFullFace(Face.NORTH))
        assertTrue(!slab.holdsTorchOnTop)
    }

    @Test
    fun `tall plants take two blocks, or none, and break together`() {
        assertEquals(listOf(ABOVE_GROUND, ABOVE_GROUND.side(Face.UP)), world.place(sunflower, GROUND, Face.UP, clickY = 0f, Placer(Face.SOUTH)))
        assertEquals(state("minecraft:sunflower", "upper_block_bit" to 1.toByte()), world.stateAt(ABOVE_GROUND.side(Face.UP)))

        // With stone above the space, there's no room for the upper half.
        val underneath = PILLAR.side(Face.EAST)
        world[underneath.side(Face.UP)] = stone
        assertEquals(emptyList(), world.place(sunflower, PILLAR, Face.EAST, clickY = 0.5f, Placer(Face.SOUTH)))
        assertEquals(air, world.stateAt(underneath))

        val broken = world.breakBlock(ABOVE_GROUND.side(Face.UP))
        assertEquals(setOf(ABOVE_GROUND, ABOVE_GROUND.side(Face.UP)), broken.broken.keys)
        assertTrue(listOf(ABOVE_GROUND, ABOVE_GROUND.side(Face.UP)).all { world.stateAt(it) == air })
    }

    @Test
    fun `breaking leaves air, and says what broke`() {
        assertEquals(mapOf(PILLAR to stone), world.breakBlock(PILLAR).broken.mapValues { it.value.state })
        assertEquals(air, world.stateAt(PILLAR))
    }

    @Test
    fun `players face whichever side their yaw is nearest`() {
        assertEquals(
            listOf(Face.SOUTH, Face.SOUTH, Face.WEST, Face.NORTH, Face.NORTH, Face.EAST, Face.EAST),
            listOf(0f, 30f, 90f, 180f, -180f, -90f, 270f).map(::facing),
        )
        assertEquals(Face.DOWN, Face.UP.opposite)
        assertEquals(Face.EAST, Face.WEST.opposite)
        assertNull(Face.of(255))
    }

    private fun torch(leaning: String) = state("minecraft:torch", "torch_facing_direction" to leaning)

    private fun button(pointing: Face) = state("minecraft:stone_button", "button_pressed_bit" to 0.toByte(), "facing_direction" to pointing.ordinal)

    private fun lever(direction: String) = state("minecraft:lever", "lever_direction" to direction, "open_bit" to 0.toByte())

    private fun stairs(upsideDown: Int, direction: Int) =
        state("minecraft:oak_stairs", "upside_down_bit" to upsideDown.toByte(), "weirdo_direction" to direction, "minecraft:corner" to "none")

    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), VERSION)

    private companion object {
        const val VERSION = 18168865
        val GROUND = BlockPos(0, MIN_Y, 0)
        val ABOVE_GROUND = BlockPos(0, MIN_Y + 1, 0)
        val PILLAR = BlockPos(4, MIN_Y + 1, 4)
    }
}
