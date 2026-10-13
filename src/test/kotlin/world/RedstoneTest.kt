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
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }

    private fun door(name: String, upper: Int, open: Int) =
        state(name, CARDINAL to "south", "door_hinge_bit" to 0.toByte(), "open_bit" to open.toByte(), UPPER_BLOCK to upper.toByte())
    private fun doors(name: String) = (0..1).flatMap { upper -> (0..1).map { open -> door(name, upper, open) } }
    private fun trapdoor(open: Int) = state("minecraft:iron_trapdoor", "direction" to 0, "open_bit" to open.toByte(), "upside_down_bit" to 0.toByte())
    private fun gate(open: Int) = state("minecraft:fence_gate", CARDINAL to "south", "in_wall_bit" to 0.toByte(), "open_bit" to open.toByte())

    private val palette = BlockPalette(
        listOf(state(AIR), stone) + levers + doors("minecraft:iron_door") + doors("minecraft:wooden_door") + (0..1).map(::trapdoor) + (0..1).map(::gate),
    )

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val lower = BlockPos(0, -63, 0)
    private val upper = BlockPos(0, -62, 0)

    /** Brings power in line, and returns the sounds blocks made. */
    private fun update() = world.updatePower().sounds.map { it.sound }

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
