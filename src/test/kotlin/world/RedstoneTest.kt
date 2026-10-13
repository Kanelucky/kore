package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedstoneTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val stone = state("minecraft:stone")
    private val glass = state("minecraft:glass")
    private val redstoneBlock = state("minecraft:redstone_block")
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }
    /** Levers on the south side of a block, pointing south. */
    private val wallLevers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "south", "open_bit" to on.toByte()) }
    private val wire = (0..MAX_POWER).map { state("minecraft:redstone_wire", REDSTONE_SIGNAL to it) }
    private val torch = state("minecraft:redstone_torch", TORCH_FACING to "top")
    private val unlitTorch = state("minecraft:unlit_redstone_torch", TORCH_FACING to "top")

    private fun door(name: String, upper: Int, open: Int) =
        state(name, CARDINAL to "south", "door_hinge_bit" to 0.toByte(), "open_bit" to open.toByte(), UPPER_BLOCK to upper.toByte())
    private fun doors(name: String) = (0..1).flatMap { upper -> (0..1).map { open -> door(name, upper, open) } }
    private fun trapdoor(open: Int) = state("minecraft:iron_trapdoor", "direction" to 0, "open_bit" to open.toByte(), "upside_down_bit" to 0.toByte())
    private fun gate(open: Int) = state("minecraft:fence_gate", CARDINAL to "south", "in_wall_bit" to 0.toByte(), "open_bit" to open.toByte())

    private val palette = BlockPalette(
        listOf(state(AIR), stone, glass, redstoneBlock, torch, unlitTorch) + levers + wallLevers + wire +
            doors("minecraft:iron_door") + doors("minecraft:wooden_door") + (0..1).map(::trapdoor) + (0..1).map(::gate),
    )

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val lower = BlockPos(0, -63, 0)
    private val upper = BlockPos(0, -62, 0)

    /** Brings power in line, and returns the sounds blocks made. */
    private fun update() = world.updatePower().sounds.map { it.sound }

    /** Ticks the world's blocks and power [times], and returns the sounds blocks made. */
    private fun tick(times: Int = 1) = (1..times).flatMap { world.tickBlocks().sounds.map { it.sound } + update() }

    private fun powerAt(pos: BlockPos) = world.stateAt(pos)?.properties?.get(REDSTONE_SIGNAL)

    private fun placeDoor(name: String) {
        world[lower] = door(name, upper = 0, open = 0)
        world[upper] = door(name, upper = 1, open = 0)
        update()
    }

    @Test
    fun `an iron door opens while a lever beside either half is on, both halves together, with its sound`() {
        placeDoor("minecraft:iron_door")
        val lever = BlockPos(1, -62, 0) // Beside the upper half, standing on air, which nothing checks here
        world[lever] = levers[1]

        assertEquals(listOf("door.open"), update())
        assertEquals(door("minecraft:iron_door", upper = 0, open = 1), world.stateAt(lower))
        assertEquals(door("minecraft:iron_door", upper = 1, open = 1), world.stateAt(upper))

        world[lever] = levers[0]
        assertEquals(listOf("door.close"), update())
        assertEquals(door("minecraft:iron_door", upper = 0, open = 0), world.stateAt(lower))
    }

    @Test
    fun `power opens a wooden door too, which a hand can still shut until the power changes`() {
        placeDoor("minecraft:wooden_door")
        val lever = BlockPos(-1, -63, 0)
        world[lever] = levers[1]
        update()
        assertEquals(1.toByte(), world.stateAt(lower)?.properties?.get("open_bit"))

        // Shut by hand, it stays shut while the lever stays on, even as blocks around it change.
        world.use(lower, Face.NORTH)
        world[BlockPos(0, -63, 2)] = stone
        assertTrue(update().isEmpty())
        assertEquals(0.toByte(), world.stateAt(lower)?.properties?.get("open_bit"))

        // The power going leaves it shut, and its coming back opens it.
        world[lever] = levers[0]
        assertTrue(update().isEmpty())
        world[lever] = levers[1]
        assertEquals(listOf("door.open"), update())
    }

    @Test
    fun `dust carries power fifteen blocks, a step less each block`() {
        for (x in 1..16) world[BlockPos(x, -63, 0)] = wire[0]
        world[BlockPos(0, -63, 0)] = levers[1]
        update()

        assertEquals((15 downTo 0).toList(), (1..16).map { powerAt(BlockPos(it, -63, 0)) })

        world[BlockPos(0, -63, 0)] = levers[0]
        update()
        assertTrue((1..16).all { powerAt(BlockPos(it, -63, 0)) == 0 })
    }

    @Test
    fun `dust powers the block it points into, which powers what's beside it but not other dust`() {
        world[BlockPos(0, -63, 0)] = levers[1]
        world[BlockPos(1, -63, 0)] = wire[0]
        world[BlockPos(2, -63, 0)] = stone
        world[BlockPos(3, -63, 0)] = wire[0]
        update()

        assertEquals(15, powerAt(BlockPos(1, -63, 0)))
        assertTrue(world.isPowered(BlockPos(2, -63, 1)))
        assertEquals(0, powerAt(BlockPos(3, -63, 0)))

        // A lever fixed to the block powers it strongly, through to the dust.
        world[BlockPos(2, -62, 0)] = levers[1]
        update()
        assertEquals(15, powerAt(BlockPos(3, -63, 0)))
    }

    @Test
    fun `dust powers a door it runs into, but not one beside a corner`() {
        placeDoor("minecraft:iron_door")
        world[BlockPos(-2, -63, 0)] = levers[1]
        world[BlockPos(-1, -63, 0)] = wire[0]
        assertEquals(listOf("door.open"), update())

        // Turned to run south instead, the dust points away from the door.
        world[BlockPos(-1, -63, 1)] = wire[0]
        assertEquals(listOf("door.close"), update())
    }

    @Test
    fun `dust steps up onto blocks and down off them, glass too, unless a block on the lower dust cuts it`() {
        world[BlockPos(-1, -63, 0)] = levers[1]
        world[BlockPos(0, -63, 0)] = wire[0]
        world[BlockPos(1, -63, 0)] = glass
        world[BlockPos(1, -62, 0)] = wire[0]
        world[BlockPos(2, -63, 0)] = wire[0]
        update()
        assertEquals(listOf(15, 14, 13), listOf(powerAt(BlockPos(0, -63, 0)), powerAt(BlockPos(1, -62, 0)), powerAt(BlockPos(2, -63, 0))))

        world[BlockPos(0, -62, 0)] = stone
        update()
        assertEquals(listOf(15, 0, 0), listOf(powerAt(BlockPos(0, -63, 0)), powerAt(BlockPos(1, -62, 0)), powerAt(BlockPos(2, -63, 0))))
    }

    @Test
    fun `a redstone block powers dust and doors beside it`() {
        placeDoor("minecraft:iron_door")
        world[BlockPos(1, -63, 0)] = redstoneBlock
        world[BlockPos(2, -63, 0)] = wire[0]
        assertEquals(listOf("door.open"), update())
        assertEquals(15, powerAt(BlockPos(2, -63, 0)))
    }

    @Test
    fun `a torch powers what's beside it and the block above, and goes out two ticks after the block it's on is powered`() {
        val on = BlockPos(0, -63, 0)
        val at = BlockPos(0, -62, 0)
        world[on] = stone
        world[at] = torch
        world[BlockPos(1, -62, 0)] = wire[0]
        world[BlockPos(0, -61, 0)] = stone
        world[BlockPos(1, -61, 0)] = wire[0]
        update()
        assertEquals(15, powerAt(BlockPos(1, -62, 0)))
        assertEquals(15, powerAt(BlockPos(1, -61, 0))) // Beside the block above, which the torch powers through

        world[BlockPos(0, -63, 1)] = wallLevers[1]
        update()
        tick()
        assertEquals(torch, world.stateAt(at))
        tick()
        assertEquals(unlitTorch, world.stateAt(at))
        assertEquals(0, powerAt(BlockPos(1, -62, 0)))

        world[BlockPos(0, -63, 1)] = wallLevers[0]
        update()
        tick(2)
        assertEquals(torch, world.stateAt(at))
    }

    @Test
    fun `a torch put out eight times in three seconds burns out, and stays out a while`() {
        val lever = BlockPos(0, -63, 1)
        val at = BlockPos(0, -62, 0)
        world[BlockPos(0, -63, 0)] = stone
        world[at] = torch
        val heard = mutableListOf<String>()
        repeat(8) {
            world[lever] = wallLevers[1]
            heard += update() + tick(2)
            world[lever] = wallLevers[0]
            heard += update() + tick(2)
        }

        assertEquals(listOf("fizz"), heard)
        assertEquals(unlitTorch, world.stateAt(at))
        tick(100)
        assertEquals(unlitTorch, world.stateAt(at))
        tick(60)
        assertEquals(torch, world.stateAt(at))
    }

    @Test
    fun `power opens trapdoors and fence gates, a gate facing as it did`() {
        val trapdoorAt = BlockPos(4, -63, 0)
        val gateAt = BlockPos(6, -63, 0)
        world[trapdoorAt] = trapdoor(0)
        world[gateAt] = gate(0)
        world[BlockPos(5, -63, 0)] = levers[1] // Between the two

        assertEquals(listOf("trapdoor.open", "fence_gate.open").sorted(), update().sorted())
        assertEquals(trapdoor(1), world.stateAt(trapdoorAt))
        assertEquals(gate(1), world.stateAt(gateAt))
    }
}
