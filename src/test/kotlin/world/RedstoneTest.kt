package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
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
    private val wallTorches = listOf("minecraft:redstone_torch", "minecraft:unlit_redstone_torch").map { state(it, TORCH_FACING to "west") }

    /** A repeater taking power in at its [back], set to wait [delay] steps. */
    private fun repeater(back: Face, delay: Int = 0, powered: Boolean = false) =
        state(if (powered) POWERED_REPEATER else UNPOWERED_REPEATER, CARDINAL to back.id, REPEATER_DELAY to delay)
    private val repeaters = AROUND.flatMap { back -> (0..3).flatMap { delay -> listOf(false, true).map { repeater(back, delay, it) } } }

    /** A comparator taking power in at its [back], comparing or [subtracting]. */
    private fun comparator(back: Face, subtracting: Boolean = false, powered: Boolean = false) = state(
        if (powered) POWERED_COMPARATOR else UNPOWERED_COMPARATOR,
        CARDINAL to back.id, "output_lit_bit" to bit(powered), SUBTRACTING to bit(subtracting),
    )
    private val comparators = AROUND.flatMap { back -> listOf(false, true).flatMap { subtracting -> listOf(false, true).map { comparator(back, subtracting, it) } } }
    private val chest = state("minecraft:chest", CARDINAL to "south")

    /** An observer watching what's on its [face] side. */
    private fun observer(face: Face, powered: Boolean = false) = state(OBSERVER, "minecraft:facing_direction" to face.id, "powered_bit" to bit(powered))
    private val observers = Face.entries.flatMap { face -> listOf(false, true).map { observer(face, it) } }
    private val cobblestone = ItemDefinition("minecraft:cobblestone", 4, componentBased = false, ItemVersion.NONE)

    private fun door(name: String, upper: Int, open: Int) =
        state(name, CARDINAL to "south", "door_hinge_bit" to 0.toByte(), "open_bit" to open.toByte(), UPPER_BLOCK to upper.toByte())
    private fun doors(name: String) = (0..1).flatMap { upper -> (0..1).map { open -> door(name, upper, open) } }
    private fun trapdoor(open: Int) = state("minecraft:iron_trapdoor", "direction" to 0, "open_bit" to open.toByte(), "upside_down_bit" to 0.toByte())
    private fun gate(open: Int) = state("minecraft:fence_gate", CARDINAL to "south", "in_wall_bit" to 0.toByte(), "open_bit" to open.toByte())

    private val palette = BlockPalette(
        listOf(state(AIR), stone, glass, redstoneBlock, torch, unlitTorch, chest) + wallTorches + repeaters + comparators + observers + levers + wallLevers + wire +
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
    fun `a repeater passes power on at full strength, after its delay, out of its front only`() {
        val at = BlockPos(1, -63, 0)
        world[at] = repeater(Face.WEST)
        world[BlockPos(-1, -63, 0)] = levers[0]
        for (x in listOf(0, 2)) world[BlockPos(x, -63, 0)] = wire[0]
        world[BlockPos(1, -63, 1)] = wire[0]
        update()

        // Through the dust behind it, the lever turns it on two ticks later.
        world[BlockPos(-1, -63, 0)] = levers[1]
        update()
        tick()
        assertEquals(repeater(Face.WEST), world.stateAt(at))
        tick()
        assertEquals(repeater(Face.WEST, powered = true), world.stateAt(at))
        assertEquals(15, powerAt(BlockPos(2, -63, 0)))
        assertEquals(0, powerAt(BlockPos(1, -63, 1))) // Beside it

        world[BlockPos(-1, -63, 0)] = levers[0]
        update()
        tick(2)
        assertEquals(repeater(Face.WEST), world.stateAt(at))
        assertEquals(0, powerAt(BlockPos(2, -63, 0)))
    }

    @Test
    fun `a repeater waits two ticks for each step of its delay, and stays on that long however short the power`() {
        val at = BlockPos(1, -63, 0)
        world[at] = repeater(Face.WEST, delay = 3)
        world[BlockPos(0, -63, 0)] = levers[1]
        update()
        world[BlockPos(0, -63, 0)] = levers[0]
        update()

        tick(7)
        assertEquals(repeater(Face.WEST, delay = 3), world.stateAt(at))
        tick()
        assertEquals(repeater(Face.WEST, delay = 3, powered = true), world.stateAt(at))
        tick(7)
        assertEquals(repeater(Face.WEST, delay = 3, powered = true), world.stateAt(at))
        tick()
        assertEquals(repeater(Face.WEST, delay = 3), world.stateAt(at))
    }

    @Test
    fun `a powered repeater facing into another's side locks it as it is`() {
        val at = BlockPos(1, -63, 0)
        val lock = BlockPos(1, -63, 1)
        world[at] = repeater(Face.WEST)
        world[lock] = repeater(Face.SOUTH) // Facing north, into its side
        world[BlockPos(1, -63, 2)] = levers[1]
        update()
        tick(2)
        assertEquals(repeater(Face.SOUTH, powered = true), world.stateAt(lock))

        world[BlockPos(0, -63, 0)] = levers[1]
        update()
        tick(4)
        assertEquals(repeater(Face.WEST), world.stateAt(at))

        world[BlockPos(1, -63, 2)] = levers[0]
        update()
        tick(4)
        assertEquals(repeater(Face.WEST, powered = true), world.stateAt(at))
    }

    @Test
    fun `a torch and a repeater feeding back into the block it's on make a clock`() {
        // A torch on the east side of a block, its power carried round by dust into a repeater facing back into the block.
        val block = BlockPos(0, -63, 0)
        val at = BlockPos(1, -63, 0)
        world[block] = stone
        world[at] = wallTorches[0]
        for (pos in listOf(BlockPos(1, -63, 1), BlockPos(1, -63, 2), BlockPos(0, -63, 2))) world[pos] = wire[0]
        world[BlockPos(0, -63, 1)] = repeater(Face.SOUTH, delay = 3)
        update()

        val states = (1..80).map { tick(); world.stateAt(at) }
        assertEquals(4, states.zipWithNext().count { (before, after) -> before == wallTorches[0] && after == wallTorches[1] })
        assertTrue(states.takeLast(20).contains(wallTorches[0])) // Not burnt out
    }

    @Test
    fun `a comparator passes on the power at its back as it is, two ticks later, unless its side is stronger`() {
        // Dust from a lever reaches the comparator at 12.
        world[BlockPos(0, -63, 0)] = levers[1]
        for (x in 1..4) world[BlockPos(x, -63, 0)] = wire[0]
        val at = BlockPos(5, -63, 0)
        world[at] = comparator(Face.WEST)
        for (x in 6..7) world[BlockPos(x, -63, 0)] = wire[0]
        update()
        tick()
        assertEquals(comparator(Face.WEST), world.stateAt(at))
        tick()
        assertEquals(comparator(Face.WEST, powered = true), world.stateAt(at))
        assertEquals(listOf(12, 11), (6..7).map { powerAt(BlockPos(it, -63, 0)) })

        // Dust at its side at 15 is stronger, which turns it off.
        world[BlockPos(5, -63, 2)] = levers[1]
        world[BlockPos(5, -63, 1)] = wire[0]
        update()
        tick(2)
        assertEquals(comparator(Face.WEST), world.stateAt(at))
        assertEquals(0, powerAt(BlockPos(6, -63, 0)))
    }

    @Test
    fun `a subtracting comparator takes the power at its side from that at its back`() {
        val at = BlockPos(0, -63, 0)
        world[at] = comparator(Face.WEST, subtracting = true)
        world[BlockPos(-1, -63, 0)] = redstoneBlock
        // A line of dust along its side, from a lever ten blocks away, reaching it at 6.
        world[BlockPos(0, -63, 11)] = levers[1]
        for (z in 1..10) world[BlockPos(0, -63, z)] = wire[0]
        world[BlockPos(1, -63, 0)] = wire[0]
        update()
        tick(2)

        assertEquals(6, powerAt(BlockPos(0, -63, 1)))
        assertEquals(9, powerAt(BlockPos(1, -63, 0)))
    }

    @Test
    fun `a comparator reads how full a container behind it is, through a block too`() {
        val at = BlockPos(0, -63, 0)
        world[at] = comparator(Face.WEST)
        world[BlockPos(1, -63, 0)] = wire[0]
        val behind = BlockPos(-1, -63, 0)
        world[behind] = chest
        update()
        tick(2)
        assertEquals(0, powerAt(BlockPos(1, -63, 0)))

        // Fourteen stacks in 27 slots is a little over half full.
        val slots = world.containerAt(behind)
        for (slot in 0 until 14) slots[slot] = SlotStack(ItemStack(cobblestone, 64), world.newStackId())
        update()
        tick(2)
        assertEquals(8, powerAt(BlockPos(1, -63, 0)))

        // One item alone reads 1, and through a block, the chest behind that.
        for (slot in 0 until 14) slots[slot] = null
        slots[0] = SlotStack(ItemStack(cobblestone), world.newStackId())
        world[BlockPos(-2, -63, 0)] = chest
        world.containerAt(BlockPos(-2, -63, 0))[0] = SlotStack(ItemStack(cobblestone), world.newStackId())
        world[behind] = stone
        update()
        tick(2)
        assertEquals(1, powerAt(BlockPos(1, -63, 0)))
    }

    @Test
    fun `a comparator facing into a repeater's side locks it`() {
        val at = BlockPos(1, -63, 0)
        world[at] = repeater(Face.WEST)
        world[BlockPos(1, -63, 1)] = comparator(Face.SOUTH) // Facing north, into its side
        world[BlockPos(1, -63, 2)] = redstoneBlock
        update()
        tick(2)

        world[BlockPos(0, -63, 0)] = levers[1]
        update()
        tick(4)
        assertEquals(repeater(Face.WEST), world.stateAt(at))
    }

    @Test
    fun `an observer pulses out of its back two ticks after what it watches changes, for two ticks`() {
        val at = BlockPos(1, -63, 0)
        world[at] = observer(Face.WEST)
        world[BlockPos(2, -63, 0)] = wire[0]
        update()

        world[BlockPos(0, -63, 0)] = stone
        update()
        tick()
        assertEquals(observer(Face.WEST), world.stateAt(at))
        tick()
        assertEquals(observer(Face.WEST, powered = true), world.stateAt(at))
        assertEquals(15, powerAt(BlockPos(2, -63, 0)))
        tick(2)
        assertEquals(observer(Face.WEST), world.stateAt(at))
        assertEquals(0, powerAt(BlockPos(2, -63, 0)))
    }

    @Test
    fun `two observers watching each other pulse on and on`() {
        val west = BlockPos(0, -63, 0)
        val east = BlockPos(1, -63, 0)
        world[west] = observer(Face.EAST)
        world[east] = observer(Face.WEST)
        update()
        world[west] = observer(Face.EAST, powered = true) // A nudge to start them
        update()

        val pulses = (1..40).count { tick(); world.stateAt(east) == observer(Face.WEST, powered = true) }
        assertTrue(pulses >= 8)
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
