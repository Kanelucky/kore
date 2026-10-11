package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionsTest {
    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val standingTorch = state("minecraft:torch", "torch_facing_direction" to "top")
    private val leaves = listOf("minecraft:oak_leaves", "minecraft:azalea_leaves_flowered").map { state(it, "persistent_bit" to 1.toByte(), "update_bit" to 0.toByte()) }

    private val palette = BlockPalette(
        listOf(air, stone, standingTorch) + leaves +
            bits(4).flatMap { (n, e, s, w) ->
                listOf("minecraft:oak_fence", "minecraft:nether_brick_fence", "minecraft:glass_pane").map { joining(it, n, e, s, w) }
            } +
            listOf("none", "short", "tall").let { heights ->
                heights.flatMap { n -> heights.flatMap { e -> heights.flatMap { s -> heights.flatMap { w -> (0..1).map { wall(n, e, s, w, it) } } } } }
            } +
            listOf("north", "east", "south", "west").flatMap { facing -> (0..1).flatMap { inWall -> (0..1).map { gate(facing, inWall, it) } } },
    )

    // One layer of stone at the bottom of the world, to stand things on.
    private val world = FlatWorld(palette, listOf(stone))
    private val spot = BlockPos(4, MIN_Y + 1, 4)

    @Test
    fun `fences join fences of their kind and whole blocks, and those around follow`() {
        place(fence(), spot)
        assertEquals(fence(), world.stateAt(spot)) // Alone

        place(fence(), spot.side(Face.EAST))
        assertEquals(fence(w = 1), world.stateAt(spot.side(Face.EAST)))
        assertEquals(listOf(spot), world.updateAround(listOf(spot.side(Face.EAST))))
        assertEquals(fence(e = 1), world.stateAt(spot))

        // Stone joins; a nether brick fence and a wall don't.
        world[spot.side(Face.NORTH)] = stone
        place(joining("minecraft:nether_brick_fence", 0, 0, 0, 0), spot.side(Face.SOUTH))
        place(wall("none", "none", "none", "none", 1), spot.side(Face.WEST))
        world.updateAround(listOf(spot.side(Face.NORTH), spot.side(Face.SOUTH), spot.side(Face.WEST)))
        assertEquals(fence(n = 1, e = 1), world.stateAt(spot))
    }

    @Test
    fun `nothing joins leaves, whole blocks though they are`() {
        world[spot.side(Face.EAST)] = leaves[0]
        world[spot.side(Face.WEST)] = leaves[1]

        place(fence(), spot)
        place(wall("none", "none", "none", "none", 1), spot.side(Face.NORTH).side(Face.EAST))

        assertEquals(fence(), world.stateAt(spot))
        assertEquals(wall("none", "none", "none", "none", 1), world.stateAt(spot.side(Face.NORTH).side(Face.EAST)))
    }

    @Test
    fun `breaking a block a fence joins takes its arm away`() {
        world[spot.side(Face.NORTH)] = stone
        place(fence(), spot)
        assertEquals(fence(n = 1), world.stateAt(spot))

        val broken = world.breakBlock(spot.side(Face.NORTH))

        assertEquals(listOf(spot), world.updateAround(broken.keys))
        assertEquals(fence(), world.stateAt(spot))
    }

    @Test
    fun `walls raise a post alone, at ends and at corners, and not along a straight run`() {
        val run = (0..2).map { BlockPos(spot.x + it, spot.y, spot.z) }
        for (pos in run) {
            place(wall("none", "none", "none", "none", 0), pos)
            world.updateAround(listOf(pos))
        }

        assertEquals(wall("none", "short", "none", "none", 1), world.stateAt(run[0]))
        assertEquals(wall("none", "short", "none", "short", 0), world.stateAt(run[1]))
        assertEquals(wall("none", "none", "none", "short", 1), world.stateAt(run[2]))

        // Turning the corner at the east end.
        place(wall("none", "none", "none", "none", 0), run[2].side(Face.SOUTH))
        world.updateAround(listOf(run[2].side(Face.SOUTH)))
        assertEquals(wall("none", "none", "short", "short", 1), world.stateAt(run[2]))

        // A torch on the straight run raises a post under it.
        world[run[1].side(Face.UP)] = standingTorch
        world.updateAround(listOf(run[1].side(Face.UP)))
        assertEquals(wall("none", "short", "none", "short", 1), world.stateAt(run[1]))
    }

    @Test
    fun `a wall stands tall under a whole block, with no post between tall sides`() {
        listOf(Face.EAST, Face.WEST, Face.UP).forEach { world[spot.side(it)] = stone }

        place(wall("none", "none", "none", "none", 1), spot)

        assertEquals(wall("none", "tall", "none", "tall", 0), world.stateAt(spot))
    }

    @Test
    fun `panes join panes, walls and whole blocks, but not fences`() {
        world[spot.side(Face.NORTH)] = stone
        place(fence(), spot.side(Face.EAST))
        place(joining("minecraft:glass_pane", 0, 0, 0, 0), spot.side(Face.SOUTH))
        place(wall("none", "none", "none", "none", 1), spot.side(Face.WEST))

        place(joining("minecraft:glass_pane", 0, 0, 0, 0), spot)

        assertEquals(joining("minecraft:glass_pane", 1, 0, 1, 1), world.stateAt(spot))
    }

    @Test
    fun `a fence gate drops between walls, and fences join it across its span`() {
        listOf(Face.EAST, Face.WEST).forEach { place(wall("none", "none", "none", "none", 1), spot.side(it)) }
        place(gate("north", 0, 0), spot)
        assertEquals(gate("north", 1, 0), world.stateAt(spot))

        // A gate facing north spans east to west, so a fence joins it from the east, not the north.
        val elsewhere = BlockPos(10, spot.y, 10)
        world[elsewhere] = gate("north", 0, 0)
        place(fence(), elsewhere.side(Face.EAST))
        place(fence(), elsewhere.side(Face.NORTH))
        assertEquals(fence(w = 1), world.stateAt(elsewhere.side(Face.EAST)))
        assertEquals(fence(), world.stateAt(elsewhere.side(Face.NORTH)))
    }

    /** Places [block] standing on the ground at [pos], with the player facing north. */
    private fun place(block: BlockState, pos: BlockPos) = world.place(block, pos.side(Face.DOWN), Face.UP, clickY = 0f, Face.NORTH)

    private fun fence(n: Int = 0, e: Int = 0, s: Int = 0, w: Int = 0) = joining("minecraft:oak_fence", n, e, s, w)

    private fun joining(name: String, n: Int, e: Int, s: Int, w: Int) = state(
        name,
        "minecraft:connection_north" to n.toByte(),
        "minecraft:connection_east" to e.toByte(),
        "minecraft:connection_south" to s.toByte(),
        "minecraft:connection_west" to w.toByte(),
    )

    private fun wall(n: String, e: String, s: String, w: String, post: Int) = state(
        "minecraft:cobblestone_wall",
        "wall_connection_type_north" to n,
        "wall_connection_type_east" to e,
        "wall_connection_type_south" to s,
        "wall_connection_type_west" to w,
        "wall_post_bit" to post.toByte(),
    )

    private fun gate(facing: String, inWall: Int, open: Int) =
        state("minecraft:fence_gate", "minecraft:cardinal_direction" to facing, "in_wall_bit" to inWall.toByte(), "open_bit" to open.toByte())

    /** Every combination of [count] bits, as lists. */
    private fun bits(count: Int): List<List<Int>> = (0 until (1 shl count)).map { n -> List(count) { n shr it and 1 } }

    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), 18168865)
}
