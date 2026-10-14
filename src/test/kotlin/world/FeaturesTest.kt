package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The game's systems switched off, each in a world of the game's own blocks: off, nothing that's part of them happens. */
class FeaturesTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    // A world of stone, dirt and grass on top, and the space above it free.
    private val ground = BlockPos(0, MIN_Y + 2, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf("minecraft:stone", "minecraft:dirt", "minecraft:grass_block").map(::block), traits = data.traits)
        world.time = 6000
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String, vararg properties: Pair<String, Any>): BlockState {
        val default = blocks.defaultOf(name) ?: error("no block $name")
        return if (properties.isEmpty()) default else blocks.stateOf(name, default.properties + properties)
    }

    private fun nameAt(pos: BlockPos) = world.stateAt(pos)?.name

    @Test
    fun `with redstone off, a lever switched on powers nothing, and plates don't press`() {
        world.features = Features(redstone = false)
        world[above] = block("minecraft:redstone_lamp")
        world[above.side(Face.EAST)] = block("minecraft:lever", LEVER_DIRECTION to "up_east_west", "open_bit" to bit(true))
        assertTrue(world.updatePower().isEmpty)
        world.tickBlocks()
        assertEquals("minecraft:redstone_lamp", nameAt(above)) // Not lit
        world[above.side(Face.WEST)] = block("minecraft:stone_pressure_plate")
        assertTrue(world.pressPlates(listOf(above.side(Face.WEST).centre)).isEmpty)
    }

    @Test
    fun `with liquids off, water stays as it's put, and with falling blocks off, sand stays in the air`() {
        world.features = Features(liquids = false, fallingBlocks = false)
        world[above] = world.liquidState(Liquid.WATER, 0)
        val sand = BlockPos(5, above.y + 3, 0)
        world[sand] = block("minecraft:sand")
        repeat(40) { world.tickBlocks() }
        assertEquals(AIR, nameAt(above.side(Face.EAST)))
        assertEquals("minecraft:sand", nameAt(sand))
        assertTrue(world.fallingBlocks.isEmpty())
    }

    @Test
    fun `with growth off, nothing grows by random ticks or bone meal, and farmland isn't trampled`() {
        world.features = Features(growth = false)
        world[ground] = block("minecraft:farmland", "moisturized_amount" to 7)
        world[above] = block("minecraft:wheat")
        world.rules.set("randomTickSpeed", 4096)
        repeat(20) { world.tickRandomly(listOf(ChunkPos(0, 0)), Random(it)) }
        assertEquals(0, world.stateAt(above)?.properties?.get(GROWTH))
        assertNull(world.fertilize(above, world.stateAt(above)!!, items))
        assertFalse(world.landOn(ground, 10f))
        assertEquals(FARMLAND, nameAt(ground))
    }

    @Test
    fun `with machines off, a furnace keeps what's put in it, and doesn't light`() {
        world.features = Features(machines = false)
        world[above] = block("minecraft:furnace")
        val furnace = world.furnaceAt(above)!!
        furnace.slots[FURNACE_INPUT] = SlotStack(items.of("minecraft:raw_iron", 3)!!, 1)
        furnace.slots[FURNACE_FUEL] = SlotStack(items.of("minecraft:coal", 1)!!, 2)
        val fuel = Fuel(data.recipes, items.of("minecraft:bucket"))
        repeat(20) { assertEquals(emptyList(), world.tickMachines(data.recipes, fuel)) }
        assertEquals("minecraft:furnace", nameAt(above))
        assertEquals(3, furnace.slots[FURNACE_INPUT]?.stack?.count)

        world.features = Features()
        world.tickMachines(data.recipes, fuel)
        assertEquals("minecraft:lit_furnace", nameAt(above)) // On, it lights at once
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
