package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CRAFTING_TABLE
import org.koremc.registry.Fuel
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.Recipes
import org.koremc.registry.ShapedRecipe
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CraftersTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val stone = state("minecraft:stone")
    private val chest = state("minecraft:chest", CARDINAL to "south")

    /** A crafter facing east, with its top up. */
    private fun crafter(crafting: Boolean = false, triggered: Boolean = false) =
        state(CRAFTER, "orientation" to "east_up", "crafting" to bit(crafting), "triggered_bit" to bit(triggered))
    private val crafters = listOf(false, true).flatMap { crafting -> listOf(false, true).map { crafter(crafting, it) } }
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }
    private val palette = BlockPalette(listOf(state(AIR), stone, chest) + crafters + levers)

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(0, -63, 0)
    private val lever = BlockPos(0, -63, 1)
    private val front = BlockPos(1, -63, 0)

    private fun item(name: String, id: Int) = ItemDefinition("minecraft:$name", id, componentBased = false, ItemVersion.NONE)
    private val planks = item("planks", 1)
    private val stick = item("stick", 2)
    private val cobblestone = item("cobblestone", 3)

    /** Sticks, from two planks one above the other. */
    private val sticks = ShapedRecipe(
        "stick", 1, 2, listOf(Ingredient.Item(planks.name, null, 1), Ingredient.Item(planks.name, null, 1)), listOf(ItemStack(stick, 4)),
        CRAFTING_TABLE, priority = 0, assumeSymmetry = true, UUID(0, 1), networkId = 1,
    )
    private val recipes = Recipes(listOf(sticks), emptyList(), emptyList(), emptyMap())
    private val fuel = Fuel(recipes)

    private val sounds = mutableListOf<String>()

    /** Ticks the world's blocks, machines and power [times], as the server does. */
    private fun tick(times: Int = 1) = repeat(times) {
        world.tickBlocks()
        val made = mutableListOf<BlockSound>()
        world.tickMachines(recipes, fuel, sounds = made)
        sounds += made.map { it.sound }
        world.updatePower()
    }

    private fun power(on: Boolean) {
        world[lever] = levers[if (on) 1 else 0]
        world.updatePower()
    }

    @Test
    fun `a crafter crafts once, four ticks after power reaches it, using up what the recipe takes, and drops what it made out of its front`() {
        world[at] = crafter()
        val grid = world.crafterAt(at)!!
        grid.slots[0] = SlotStack(ItemStack(planks, 2), 1)
        grid.slots[3] = SlotStack(ItemStack(planks, 1), 2)
        power(on = true)
        assertEquals(crafter(triggered = true), world.stateAt(at))

        tick(3)
        assertTrue(world.items.isEmpty())
        tick()
        assertEquals(listOf("crafter.craft"), sounds)
        assertEquals(listOf(4), world.items.map { it.stack.count })
        assertEquals(stick, world.items.single().stack.item)
        assertTrue(world.items.single().position.x > 1f, "it comes out of the crafter's east face")
        assertEquals(listOf(1, null), listOf(grid.slots[0]?.stack?.count, grid.slots[3]?.stack?.count))
        assertEquals(crafter(crafting = true, triggered = true), world.stateAt(at))

        // It shows it's crafted for a moment, and crafts no more while the power stays.
        tick(10)
        assertEquals(crafter(triggered = true), world.stateAt(at))
        assertEquals(1, world.items.size)
    }

    @Test
    fun `a crafter with nothing it can craft clicks that it failed, and uses nothing up`() {
        world[at] = crafter()
        world.crafterAt(at)!!.slots[0] = SlotStack(ItemStack(planks, 1), 1)
        power(on = true)
        tick(4)
        assertEquals(listOf("crafter.fail"), sounds)
        assertEquals(1, world.crafterAt(at)!!.slots[0]?.stack?.count)
        assertTrue(world.items.isEmpty())
    }

    @Test
    fun `a crafter facing a container crafts into it`() {
        world[at] = crafter()
        world[front] = chest
        val grid = world.crafterAt(at)!!
        grid.slots[1] = SlotStack(ItemStack(planks, 1), 1)
        grid.slots[4] = SlotStack(ItemStack(planks, 1), 2)
        power(on = true)
        tick(4)
        assertEquals(4, world.containerAt(front)[0]?.stack?.count)
        assertTrue(world.items.isEmpty())
    }

    @Test
    fun `hoppers fill a crafter's empty slots that are on first, then its smallest stack, and a comparator reads how many are full or off`() {
        world[at] = crafter()
        val grid = world.crafterAt(at)!!
        assertTrue(world.toggleCrafterSlot(at, 1, disabled = true))
        assertEquals(2, world.blockEntityAt(at)?.getShort("disabled_slots")?.toInt())
        assertEquals(1, world.fullness(at))

        val reach = world.hopperReach(at, Face.UP, recipes, fuel)!!
        repeat(8) { assertTrue(world.passOne(reach, ItemStack(cobblestone))) }
        assertEquals(listOf(1, null, 1, 1, 1, 1, 1, 1, 1), (0 until CRAFTER_SLOTS).map { grid.slots[it]?.stack?.count })
        grid.slots[0] = SlotStack(ItemStack(cobblestone, 3), 9)
        world.passOne(reach, ItemStack(cobblestone))
        assertEquals(2, grid.slots[2]?.stack?.count)
        assertEquals(9, world.fullness(at))

        // A full slot can't be switched off.
        assertFalse(world.toggleCrafterSlot(at, 0, disabled = true))
    }
}
