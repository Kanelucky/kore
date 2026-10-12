package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.Fuel
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.PotionMix
import org.koremc.registry.ContainerMix
import org.koremc.registry.Recipes
import org.koremc.registry.ShapelessRecipe
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachinesTest {
    private fun item(name: String, id: Int, stack: Int = 64) = ItemDefinition("minecraft:$name", id, componentBased = false, ItemVersion.NONE, maxStackSize = stack)

    private val rawIron = item("raw_iron", 1)
    private val ironIngot = item("iron_ingot", 2)
    private val coal = item("coal", 3)
    private val lava = item("lava_bucket", 4, stack = 1)
    private val bucket = item("bucket", 5, stack = 16)
    private val stick = item("stick", 6)
    private val potion = item("potion", 7, stack = 1)
    private val wart = item("nether_wart", 8)
    private val gunpowder = item("gunpowder", 9)
    private val splash = item("splash_potion", 10, stack = 1)
    private val blazePowder = item("blaze_powder", 11)

    private fun cooking(block: String, id: Int) =
        ShapelessRecipe("smelt_$block", listOf(Ingredient.Item(rawIron.name, null, 1)), listOf(ItemStack(ironIngot)), block, 0, UUID(0, id.toLong()), keepsData = false, id)

    private val recipes = Recipes(
        listOf(cooking("furnace", 1), cooking("blast_furnace", 2)),
        potionMixes = listOf(PotionMix(ItemStack(potion, aux = 0), ItemStack(wart), ItemStack(potion, aux = 4))),
        containerMixes = listOf(ContainerMix(potion, gunpowder, splash)),
        tags = emptyMap(),
    )
    private val fuel = Fuel(recipes, ItemStack(bucket))
    private var nextId = 100
    private val newStackId = { nextId++ }

    private fun Furnace.put(slot: Int, stack: ItemStack) {
        slots[slot] = SlotStack(stack, newStackId())
    }

    /** Ticks [furnace] [ticks] times, returning how many times it lit or went out. */
    private fun Furnace.run(ticks: Int) = (1..ticks).count { tick(recipes, fuel, newStackId) }

    @Test
    fun `a furnace burns its fuel to cook what it holds, one item every ten seconds`() {
        val furnace = Furnace(FurnaceKind.FURNACE)
        furnace.put(FURNACE_INPUT, ItemStack(rawIron, 3))
        furnace.put(FURNACE_FUEL, ItemStack(coal, 2))

        assertEquals(1, furnace.run(1)) // It lit
        assertTrue(furnace.lit)
        assertEquals(1600, furnace.litDuration)
        assertEquals(1, furnace.slots[FURNACE_FUEL]?.stack?.count)

        furnace.run(199)
        assertEquals(ItemStack(ironIngot), furnace.slots[FURNACE_OUTPUT]?.stack)
        assertEquals(2, furnace.slots[FURNACE_INPUT]?.stack?.count)
        assertEquals(0.7f, furnace.takeExperience(), 1e-4f)

        furnace.run(400)
        assertEquals(3, furnace.slots[FURNACE_OUTPUT]?.stack?.count)
        assertNull(furnace.slots[FURNACE_INPUT])
        // With nothing more to cook, the coal burns on, unused.
        assertEquals(1, furnace.slots[FURNACE_FUEL]?.stack?.count)
        assertTrue(furnace.lit)
    }

    @Test
    fun `a blast furnace cooks twice as fast, burning its fuel twice as fast`() {
        val furnace = Furnace(FurnaceKind.BLAST_FURNACE)
        furnace.put(FURNACE_INPUT, ItemStack(rawIron, 2))
        furnace.put(FURNACE_FUEL, ItemStack(coal))

        furnace.run(100)

        assertEquals(800, furnace.litDuration)
        assertEquals(ItemStack(ironIngot), furnace.slots[FURNACE_OUTPUT]?.stack)
    }

    @Test
    fun `nothing that cooks, or nothing to burn, keeps a furnace out`() {
        val furnace = Furnace(FurnaceKind.FURNACE)
        furnace.put(FURNACE_INPUT, ItemStack(stick))
        furnace.put(FURNACE_FUEL, ItemStack(coal))
        assertEquals(0, furnace.run(10))

        furnace.put(FURNACE_INPUT, ItemStack(rawIron))
        furnace.slots[FURNACE_FUEL] = null
        assertEquals(0, furnace.run(10))
        assertFalse(furnace.lit)
    }

    @Test
    fun `cooking cools off once the fuel's out, and stops when what's made has no room`() {
        val furnace = Furnace(FurnaceKind.FURNACE)
        furnace.put(FURNACE_INPUT, ItemStack(rawIron, 2))
        furnace.put(FURNACE_FUEL, ItemStack(stick)) // Which burns for five seconds: half an item
        furnace.run(100)
        assertEquals(100, furnace.cookTime)
        furnace.run(2)
        assertFalse(furnace.lit)
        assertEquals(96, furnace.cookTime)

        val full = Furnace(FurnaceKind.FURNACE)
        full.put(FURNACE_INPUT, ItemStack(rawIron))
        full.put(FURNACE_FUEL, ItemStack(coal))
        full.put(FURNACE_OUTPUT, ItemStack(ironIngot, 64))
        assertEquals(0, full.run(10))
        assertEquals(0, full.cookTime)
    }

    @Test
    fun `lava leaves its bucket`() {
        val furnace = Furnace(FurnaceKind.FURNACE)
        furnace.put(FURNACE_INPUT, ItemStack(rawIron))
        furnace.put(FURNACE_FUEL, ItemStack(lava))

        furnace.run(1)

        assertEquals(20000, furnace.litDuration)
        assertEquals(ItemStack(bucket), furnace.slots[FURNACE_FUEL]?.stack)
    }

    @Test
    fun `a brewing stand fuelled with blaze powder brews every bottle the reagent brews into`() {
        val stand = BrewingStand()
        stand.slots[BREWING_REAGENT] = SlotStack(ItemStack(wart, 2), newStackId())
        stand.slots[1] = SlotStack(ItemStack(potion, aux = 0), newStackId())
        stand.slots[3] = SlotStack(ItemStack(potion, aux = 0), newStackId())
        stand.slots[BREWING_FUEL] = SlotStack(ItemStack(blazePowder), newStackId())

        stand.tick(recipes, newStackId)
        assertEquals(BREW_TICKS, stand.brewTime)
        assertEquals(BREWS_PER_FUEL - 1, stand.fuel)
        assertNull(stand.slots[BREWING_FUEL])
        assertEquals(setOf(1, 3), stand.bottlesShown)

        val finished = (1..BREW_TICKS).count { stand.tick(recipes, newStackId) }
        assertEquals(1, finished)
        assertEquals(ItemStack(potion, aux = 4), stand.slots[1]?.stack)
        assertEquals(ItemStack(potion, aux = 4), stand.slots[3]?.stack)
        assertEquals(1, stand.slots[BREWING_REAGENT]?.stack?.count)
    }

    @Test
    fun `gunpowder makes a potion a splash potion of the same kind, and a reagent taken out stops the brew`() {
        val stand = BrewingStand()
        stand.slots[BREWING_REAGENT] = SlotStack(ItemStack(gunpowder), newStackId())
        stand.slots[2] = SlotStack(ItemStack(potion, aux = 4), newStackId())
        stand.slots[BREWING_FUEL] = SlotStack(ItemStack(blazePowder), newStackId())
        stand.tick(recipes, newStackId)
        repeat(10) { stand.tick(recipes, newStackId) }

        stand.slots[BREWING_REAGENT] = null
        stand.tick(recipes, newStackId)
        assertEquals(0, stand.brewTime)

        stand.slots[BREWING_REAGENT] = SlotStack(ItemStack(gunpowder), newStackId())
        repeat(BREW_TICKS + 1) { stand.tick(recipes, newStackId) }
        assertEquals(ItemStack(splash, aux = 4), stand.slots[2]?.stack)
    }

    @Test
    fun `a furnace lighting up keeps what it holds, and breaking it gives back what it made's experience`() {
        val air = org.koremc.registry.BlockState(org.koremc.registry.AIR, org.cloudburstmc.nbt.NbtMap.EMPTY, 1)
        val unlit = org.koremc.registry.BlockState("minecraft:furnace", org.cloudburstmc.nbt.NbtMap.fromMap(mapOf("minecraft:cardinal_direction" to "north")), 1)
        val lit = org.koremc.registry.BlockState("minecraft:lit_furnace", org.cloudburstmc.nbt.NbtMap.fromMap(mapOf("minecraft:cardinal_direction" to "north")), 1)
        val world = FlatWorld(org.koremc.registry.BlockPalette(listOf(air, unlit, lit)), listOf(unlit))
        val pos = BlockPos(0, -63, 0)
        world[pos] = unlit
        val furnace = world.furnaceAt(pos)!!
        furnace.slots[FURNACE_INPUT] = SlotStack(ItemStack(rawIron, 2), newStackId())
        furnace.slots[FURNACE_FUEL] = SlotStack(ItemStack(coal), newStackId())

        assertEquals(listOf(pos), world.tickMachines(recipes, fuel))
        assertEquals(lit, world.stateAt(pos))
        assertTrue(world.furnaceAt(pos) === furnace)
        repeat(200) { world.tickMachines(recipes, fuel) }

        val broken = world.breakBlock(pos).broken.getValue(pos)
        assertEquals(listOf(ItemStack(rawIron), null, ItemStack(ironIngot)), broken.contents)
        assertEquals(0.7f, broken.experience, 1e-4f)
    }
}
