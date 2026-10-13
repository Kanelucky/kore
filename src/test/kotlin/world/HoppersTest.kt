package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.Recipes
import org.koremc.registry.ShapelessRecipe
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoppersTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)
    private fun hopper(facing: Face, locked: Boolean = false) = state(HOPPER, FACING_DIRECTION to facing.ordinal, "toggle_bit" to bit(locked))

    private val stone = state("minecraft:stone")
    private val chest = state("minecraft:chest", CARDINAL to "south")
    private val enderChest = state("minecraft:ender_chest", CARDINAL to "south")
    private val shulkerBox = state("minecraft:white_shulker_box")
    private val furnace = state("minecraft:furnace", CARDINAL to "south")
    private val down = hopper(Face.DOWN)
    private val west = hopper(Face.WEST)
    private val locked = hopper(Face.DOWN, locked = true)
    private val palette = BlockPalette(listOf(state(AIR), stone, chest, enderChest, shulkerBox, furnace, down, west, locked))

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val ground = BlockPos(0, -63, 0)
    private val above = BlockPos(0, -62, 0)

    private fun item(name: String, id: Int, stack: Int = 64) = ItemDefinition("minecraft:$name", id, componentBased = false, ItemVersion.NONE, maxStackSize = stack)
    private val cobblestone = item("cobblestone", 1)
    private val rawIron = item("raw_iron", 2)
    private val ironIngot = item("iron_ingot", 3)
    private val coal = item("coal", 4)
    private val shulkerItem = item("white_shulker_box", 5, stack = 1)

    private val recipes = Recipes(
        listOf(ShapelessRecipe("iron", listOf(Ingredient.Item(rawIron.name, null, 1)), listOf(ItemStack(ironIngot)), "furnace", 0, UUID(0, 1), keepsData = false, 1)),
        emptyList(), emptyList(), emptyMap(),
    )
    private val fuel = Fuel(recipes, null)

    private fun tick(times: Int = 1) = repeat(times) { world.tickMachines(recipes, fuel) }

    private fun Hopper.holding(vararg stacks: ItemStack) = apply { stacks.forEachIndexed { slot, stack -> slots[slot] = SlotStack(stack, world.newStackId()) } }

    private fun counts(slots: List<SlotStack?>) = slots.mapNotNull { it?.let { slot -> slot.stack.item.name.removePrefix("minecraft:") to slot.stack.count } }

    @Test
    fun `a hopper passes an item at a time into the chest it points into, eight ticks apart`() {
        world[ground] = chest
        world[above] = down
        world.hopperAt(above)!!.holding(ItemStack(cobblestone, 3))

        tick()
        assertEquals(listOf("cobblestone" to 1), counts(world.containerAt(ground).contents()))
        tick(7)
        assertEquals(listOf("cobblestone" to 1), counts(world.containerAt(ground).contents()))
        tick()
        assertEquals(listOf("cobblestone" to 2), counts(world.containerAt(ground).contents()))
        assertEquals(listOf("cobblestone" to 1), counts(world.hopperAt(above)!!.slots.contents()))
    }

    @Test
    fun `a hopper takes from below a furnace what it made, and nothing it's still to cook or burn`() {
        world[above] = furnace
        world[ground] = down
        val oven = world.furnaceAt(above)!!
        oven.slots[FURNACE_INPUT] = SlotStack(ItemStack(rawIron, 4), 1)
        oven.slots[FURNACE_FUEL] = SlotStack(ItemStack(coal, 4), 2)
        oven.slots[FURNACE_OUTPUT] = SlotStack(ItemStack(ironIngot, 1), 3)

        tick(20)

        assertEquals(listOf("iron_ingot" to 1), counts(world.hopperAt(ground)!!.slots.contents()))
        assertNull(oven.slots[FURNACE_OUTPUT])
        assertEquals(4, oven.slots[FURNACE_INPUT]?.stack?.count)
    }

    @Test
    fun `hoppers fill a furnace with what it cooks from above and its fuel from the side`() {
        val side = BlockPos(1, -63, 0)
        world[ground] = furnace
        world[above] = down
        world[side] = west
        world.hopperAt(above)!!.holding(ItemStack(rawIron))
        // Raw iron doesn't burn, so it stays put, while the coal behind it goes in.
        world.hopperAt(side)!!.holding(ItemStack(rawIron), ItemStack(coal))

        tick()

        val oven = world.furnaceAt(ground)!!
        assertEquals(ItemStack(rawIron), oven.slots[FURNACE_INPUT]?.stack)
        assertEquals(ItemStack(coal), oven.slots[FURNACE_FUEL]?.stack)
        assertEquals(listOf("raw_iron" to 1), counts(world.hopperAt(side)!!.slots.contents()))
    }

    @Test
    fun `a hopper takes in the whole of a stack lying in its bowl, which items land in`() {
        world[ground] = down
        val dropped = world.spawnItem(ItemStack(cobblestone, 10), Vec3(0.5f, -61f, 0.5f), Vec3(0f, 0f, 0f), DROPPED_PICKUP_DELAY)
        repeat(20) { world.tickItems() }
        assertEquals(-63f + HOPPER_BOWL.toFloat(), dropped.position.y, 1e-3f)

        tick()

        assertEquals(listOf("cobblestone" to 10), counts(world.hopperAt(ground)!!.slots.contents()))
        assertTrue(world.items.isEmpty())
    }

    @Test
    fun `a hopper takes what fits of a stack, and leaves the rest lying`() {
        world[ground] = down
        world.hopperAt(ground)!!.holding(*Array(4) { ItemStack(coal, 64) }, ItemStack(cobblestone, 60))
        val dropped = world.spawnItem(ItemStack(cobblestone, 10), Vec3(0.5f, -62f, 0.5f), Vec3(0f, 0f, 0f), 0)

        tick()

        assertEquals(64, world.hopperAt(ground)!!.slots[4]?.stack?.count)
        assertEquals(6, dropped.stack.count)
        assertTrue(dropped.restacked)
    }

    @Test
    fun `a hopper takes in only items over it, not those brushing past its edge`() {
        world[ground] = down
        // Its side reaches over the hopper, but the item itself is beside it, in the air above the hopper's level.
        val beside = world.spawnItem(ItemStack(cobblestone), Vec3(-0.05f, -61.5f, 0.5f), Vec3(0f, 0f, 0f), 0)
        val overhead = world.spawnItem(ItemStack(coal), Vec3(0.5f, -61.5f, 0.5f), Vec3(0f, 0f, 0f), 0)

        tick()
        tick(8)

        assertEquals(listOf(beside), world.items.toList())
        assertEquals(listOf("coal" to 1), counts(world.hopperAt(ground)!!.slots.contents()))
    }

    @Test
    fun `items go down a line of hoppers at one pace, whichever works first`() {
        val side = BlockPos(1, -62, 0)
        world[ground] = chest
        world[above] = down
        world[side] = west
        world.hopperAt(side)!!.holding(ItemStack(cobblestone))

        // Into the hopper over the chest in the first tick, which waits eight before passing it on.
        tick(7)
        assertTrue(world.containerAt(ground).contents().all { it == null })
        tick()
        assertEquals(listOf("cobblestone" to 1), counts(world.containerAt(ground).contents()))
    }

    @Test
    fun `a hopper redstone locks moves nothing`() {
        world[ground] = chest
        world[above] = locked
        world.hopperAt(above)!!.holding(ItemStack(cobblestone))

        tick(10)

        assertTrue(world.containerAt(ground).contents().all { it == null })
    }

    @Test
    fun `shulker boxes don't take shulker boxes, and hoppers don't reach into ender chests`() {
        world[ground] = shulkerBox
        world[above] = down
        world.hopperAt(above)!!.holding(ItemStack(shulkerItem), ItemStack(cobblestone))

        tick()
        assertEquals(listOf("cobblestone" to 1), counts(world.containerAt(ground).contents()))

        world[ground] = enderChest
        tick(8)
        assertEquals(listOf("white_shulker_box" to 1), counts(world.hopperAt(above)!!.slots.contents()))
    }
}
