package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.Recipes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispensersTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val stone = state("minecraft:stone")
    private val glass = state("minecraft:glass")
    private val chest = state("minecraft:chest", CARDINAL to "south")
    private val rail = state("minecraft:rail", "rail_direction" to 0)

    /** Levers on the floor, off then on, and on the west side of a block, pointing east. */
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }
    private val wallLevers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "east", "open_bit" to on.toByte()) }
    private val buttons = (0..1).map { pressed -> state("minecraft:stone_button", "facing_direction" to Face.UP.ordinal, "button_pressed_bit" to pressed.toByte()) }

    private fun machines(name: String) = Face.entries.flatMap { facing -> (0..1).map { on -> state(name, FACING_DIRECTION to facing.ordinal, TRIGGERED to on.toByte()) } }
    private val dispensers = machines("minecraft:dispenser")
    private val droppers = machines("minecraft:dropper")
    private fun dispenser(facing: Face, triggered: Boolean = false) = dispensers[facing.ordinal * 2 + if (triggered) 1 else 0]
    private fun dropper(facing: Face) = droppers[facing.ordinal * 2]

    private val palette = BlockPalette(listOf(state(AIR), stone, glass, chest, rail) + levers + wallLevers + buttons + dispensers + droppers)

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(0, -63, 0)

    private fun item(name: String, id: Int, stack: Int = 64) = ItemDefinition("minecraft:$name", id, componentBased = false, ItemVersion.NONE, maxStackSize = stack)
    private val cobblestone = item("cobblestone", 1)
    private val arrow = item("arrow", 2)
    private val minecart = item("minecart", 3, stack = 1)

    private val recipes = Recipes(emptyList(), emptyList(), emptyList(), emptyMap())
    private val fuel = Fuel(recipes)

    /** Ticks the world's machines and power [times], and returns what fired. */
    private fun tick(times: Int = 1): List<Fired> {
        val fired = mutableListOf<Fired>()
        repeat(times) {
            world.tickMachines(recipes, fuel, fired = fired)
            world.updatePower()
        }
        return fired
    }

    private fun Dispenser.holding(vararg stacks: ItemStack) = apply { stacks.forEachIndexed { slot, stack -> slots[slot] = SlotStack(stack, world.newStackId()) } }

    @Test
    fun `a lever or button beside a block powers it, as does one on a block beside it, unless that's glass`() {
        world[at] = stone
        val beside = BlockPos(1, -63, 0)
        world[beside] = stone
        world[BlockPos(1, -62, 0)] = levers[1] // On top of the stone beside, which it powers through
        assertTrue(world.isPowered(at))
        assertTrue(world.isPowered(beside.side(Face.UP).side(Face.EAST))) // Beside the lever itself

        world[beside] = glass
        assertFalse(world.isPowered(at))

        world[BlockPos(1, -62, 0)] = levers[0]
        world[BlockPos(-1, -63, 0)] = buttons[1]
        assertTrue(world.isPowered(at))
    }

    @Test
    fun `a lever only powers through the block it's fixed to`() {
        world[at] = stone
        // Fixed to the east side of the stone at x 0, z 1, pointing east, and only touching the stone at x 1, z 0.
        world[BlockPos(1, -63, 0)] = stone
        world[BlockPos(1, -63, 1)] = wallLevers[1]
        world[BlockPos(0, -63, 1)] = stone
        assertTrue(world.isPowered(BlockPos(-1, -63, 1)))
        assertTrue(world.isPowered(BlockPos(0, -63, 2)))
        assertFalse(world.isPowered(BlockPos(2, -63, 0)))
    }

    @Test
    fun `a dispenser fires once, four ticks after power reaches it, dropping an item out of its front`() {
        world[at] = dispenser(Face.EAST)
        world.dispenserAt(at)!!.holding(ItemStack(cobblestone, 2))
        val lever = BlockPos(0, -62, 0)
        world[lever] = levers[1]

        assertTrue(tick().isEmpty())
        assertEquals(dispenser(Face.EAST, triggered = true), world.stateAt(at))
        assertTrue(tick(3).isEmpty())
        assertEquals(listOf(Fired(at, Face.EAST, dispensed = true)), tick())

        val dropped = world.items.single()
        assertEquals(ItemStack(cobblestone), dropped.stack)
        assertEquals(1.2f, dropped.position.x, 1e-3f)
        assertTrue(dropped.velocity.x in 0.09f..0.41f)
        assertEquals(1, world.dispenserAt(at)!!.slots[0]?.stack?.count)

        // Power left on fires nothing more, until it goes and comes back.
        assertTrue(tick(10).isEmpty())
        world[lever] = levers[0]
        tick()
        assertEquals(dispenser(Face.EAST), world.stateAt(at))
        world[lever] = levers[1]
        assertEquals(1, tick(5).size)
        assertTrue(world.dispenserAt(at)!!.slots.contents().all { it == null })
    }

    @Test
    fun `an empty dispenser clicks, and one keeps what Kore doesn't fire yet`() {
        world[at] = dispenser(Face.UP)
        world[BlockPos(1, -63, 0)] = buttons[1]
        assertEquals(listOf(Fired(at, Face.UP, dispensed = false)), tick(5))

        world[at] = dispenser(Face.EAST)
        world[BlockPos(1, -63, 0)] = rail
        world.dispenserAt(at)!!.holding(ItemStack(arrow), ItemStack(minecart))
        world[BlockPos(-1, -63, 0)] = buttons[1]
        repeat(2) {
            assertTrue(tick(5).isEmpty())
            world[BlockPos(-1, -63, 0)] = buttons[0]
            tick()
            world[BlockPos(-1, -63, 0)] = buttons[1]
        }
        assertTrue(world.items.isEmpty())
        assertEquals(2, world.dispenserAt(at)!!.slots.contents().count { it != null })
    }

    @Test
    fun `a dropper passes an item into the container it faces, quietly, and keeps it while that's full`() {
        val front = BlockPos(1, -63, 0)
        world[at] = dropper(Face.EAST)
        world[front] = chest
        world.dispenserAt(at)!!.holding(ItemStack(cobblestone, 2))
        world[BlockPos(0, -62, 0)] = levers[1]

        assertTrue(tick(5).isEmpty())
        assertEquals(ItemStack(cobblestone), world.containerAt(front)[0]?.stack)
        assertTrue(world.items.isEmpty())

        val full = world.containerAt(front)
        for (slot in 0 until full.size) full[slot] = SlotStack(ItemStack(arrow, 64), world.newStackId())
        world[BlockPos(0, -62, 0)] = levers[0]
        tick()
        world[BlockPos(0, -62, 0)] = levers[1]
        tick(5)
        assertEquals(1, world.dispenserAt(at)!!.slots[0]?.stack?.count)
    }

    @Test
    fun `a dropper with nothing in front drops the item, as a dispenser does`() {
        world[at] = dropper(Face.NORTH)
        world.dispenserAt(at)!!.holding(ItemStack(cobblestone))
        world[BlockPos(0, -62, 0)] = levers[1]

        assertEquals(listOf(Fired(at, Face.NORTH, dispensed = true)), tick(5))
        assertNull(world.dispenserAt(at)!!.slots[0])
        assertEquals(-0.2f, world.items.single().position.z, 1e-3f)
    }
}
