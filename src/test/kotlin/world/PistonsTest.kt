package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PistonsTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val obsidian = state("minecraft:obsidian")
    private val slime = state("minecraft:slime")
    private val torch = state("minecraft:torch", TORCH_FACING to "top")
    private val movingBlock = state("minecraft:moving_block")
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }
    private val observers = listOf(false, true).map { state(OBSERVER, "minecraft:facing_direction" to "up", "powered_bit" to bit(it)) }

    /** Facings as pistons store them, sideways the wrong way round. */
    private fun stored(facing: Face) = if (facing.horizontal) facing.opposite.ordinal else facing.ordinal
    private fun piston(facing: Face, sticky: Boolean = false) = state(if (sticky) STICKY_PISTON else PISTON, FACING_DIRECTION to stored(facing))
    private fun head(facing: Face, sticky: Boolean = false) =
        state(if (sticky) "minecraft:sticky_piston_arm_collision" else "minecraft:piston_arm_collision", FACING_DIRECTION to stored(facing))

    private val palette = BlockPalette(
        listOf(air, stone, obsidian, slime, torch, movingBlock) + levers + observers +
            Face.entries.flatMap { facing -> listOf(false, true).flatMap { sticky -> listOf(piston(facing, sticky), head(facing, sticky)) } },
    )

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(0, -63, 0)
    private val lever = BlockPos(0, -63, 1)

    private val broken = mutableListOf<BlockPos>()

    /** Ticks the world's blocks and power [times]. */
    private fun tick(times: Int = 1) = repeat(times) {
        broken += world.tickBlocks().broken.keys
        world.updatePower()
    }

    private fun east(x: Int) = BlockPos(x, -63, 0)

    private fun placePiston(facing: Face = Face.EAST, sticky: Boolean = false) {
        world[at] = piston(facing, sticky)
        world.updatePower()
    }

    /** Switches the lever beside the piston, and waits for the piston to finish moving. */
    private fun switch(on: Boolean) {
        world[lever] = levers[if (on) 1 else 0]
        world.updatePower()
        tick(5)
    }

    @Test
    fun `a piston pushes the blocks in front of it, two ticks after power reaches it, and they slide out over two more`() {
        placePiston()
        world[east(1)] = stone
        world[east(2)] = stone
        world[lever] = levers[1]
        world.updatePower()

        tick(2)
        assertEquals(head(Face.EAST), world.stateAt(east(1)))
        assertEquals(listOf(movingBlock, movingBlock), listOf(world.stateAt(east(2)), world.stateAt(east(3))))
        assertEquals(1.toByte(), world.blockEntityAt(at)?.getByte("State")) // Moving out
        assertEquals("minecraft:stone", world.blockEntityAt(east(3))?.getCompound("movingBlock")?.getString("name"))

        val progress = (1..3).map { tick(); world.blockEntityAt(at)!!.getFloat("Progress") to world.blockEntityAt(at)!!.getFloat("LastProgress") }
        assertEquals(listOf(0.5f to 0f, 1f to 0.5f, 1f to 1f), progress)
        assertEquals(2.toByte(), world.blockEntityAt(at)?.getByte("State"))
        assertEquals(listOf(stone, stone), listOf(world.stateAt(east(2)), world.stateAt(east(3))))
        assertNull(world.blockEntityAt(east(3)))
    }

    @Test
    fun `a piston pulls its head back in, and a sticky one the block in front of it too`() {
        placePiston()
        world[east(1)] = stone
        switch(on = true)
        switch(on = false)
        assertEquals(air, world.stateAt(east(1)))
        assertEquals(stone, world.stateAt(east(2)))
        assertEquals(0.toByte(), world.blockEntityAt(at)?.getByte("State"))

        placePiston(sticky = true)
        world[east(1)] = stone
        world[east(2)] = air
        switch(on = true)
        assertEquals(head(Face.EAST, sticky = true), world.stateAt(east(1)))
        switch(on = false)
        assertEquals(stone, world.stateAt(east(1)))
        assertEquals(air, world.stateAt(east(2)))
    }

    @Test
    fun `twelve blocks move, thirteen don't, and obsidian doesn't`() {
        placePiston()
        for (x in 1..12) world[east(x)] = stone
        switch(on = true)
        assertEquals(stone, world.stateAt(east(13)))

        switch(on = false)
        world[east(14)] = stone
        world[east(1)] = stone
        switch(on = true)
        assertEquals(stone, world.stateAt(east(1)))

        world[at] = air
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = obsidian
        switch(on = true)
        assertEquals(obsidian, world.stateAt(BlockPos(0, -62, 0)))
    }

    @Test
    fun `a piston breaks what it can't move but breaks, like a torch`() {
        placePiston()
        world[east(1)] = stone
        world[east(2)] = torch
        switch(on = true)
        assertEquals(listOf(east(2)), broken)
        assertEquals(stone, world.stateAt(east(2)))
    }

    @Test
    fun `slime drags the blocks beside it along, and on the ground, the ground, which won't move`() {
        placePiston()
        world[east(1)] = slime
        switch(on = true)
        assertEquals(slime, world.stateAt(east(1)))

        // A block up, off the ground, it moves, and the block on top of it with it.
        world[lever] = levers[0]
        world[at] = air
        world[east(1)] = air
        val raised = BlockPos(0, -62, 0)
        world[raised] = piston(Face.EAST)
        world[BlockPos(1, -62, 0)] = slime
        world[BlockPos(1, -61, 0)] = stone
        world[BlockPos(0, -62, 1)] = levers[1]
        world.updatePower()
        tick(5)
        assertEquals(slime, world.stateAt(BlockPos(2, -62, 0)))
        assertEquals(stone, world.stateAt(BlockPos(2, -61, 0)))
        assertEquals(air, world.stateAt(BlockPos(1, -61, 0)))
    }

    @Test
    fun `an observer a piston moves pulses once it lands, as in Bedrock`() {
        placePiston()
        world[east(1)] = observers[0]
        switch(on = true)
        assertEquals(observers[0], world.stateAt(east(2))) // Landed, and waiting to pulse
        tick(2)
        assertEquals(observers[1], world.stateAt(east(2)))
    }

    @Test
    fun `a piston that's out and its head break together`() {
        placePiston()
        switch(on = true)
        val changes = world.breakBlock(east(1))
        assertTrue(at in changes.broken)
        assertEquals(air, world.stateAt(at))
    }
}
