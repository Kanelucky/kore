package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.vanillaData
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Composters, by the game's own blocks. */
class CompostersTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")))
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    private fun item(name: String): ItemStack = items.of(name) ?: error("no item $name")

    private val level get() = world.stateAt(above)?.properties?.get("composter_fill_level")

    private fun use(name: String?, random: Random = Random(1)) = world.useComposter(above, world.stateAt(above)!!, name?.let(::item), items, random)

    /** Chance that never comes up. */
    private val unlucky = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = 0.99
    }

    @Test
    fun `what composts raises a composter a level by its chance, readying it a second after it's full`() {
        world[above] = block("minecraft:composter")
        assertNull(use("minecraft:stone"))

        // Cake always raises it.
        val first = use("minecraft:cake")
        assertEquals(HeldAfter.USED_UP, first?.held)
        assertEquals("block.composter.fill_success", first?.sound?.sound)
        assertEquals(listOf(WorldEvent(above, 2005, 0)), first?.events)
        repeat(6) { use("minecraft:cake") }
        assertEquals(7, level)
        assertEquals(emptyList(), use("minecraft:cake")?.changed) // Full, it takes the click, and nothing more
        assertEquals(7, level)

        repeat(COMPOST_TICKS - 1) { world.tickBlocks() }
        assertEquals(7, level)
        assertEquals(listOf("block.composter.ready"), world.tickBlocks().sounds.map { it.sound })
        assertEquals(8, level)

        val emptied = use(null)
        assertEquals(listOf("minecraft:bone_meal"), emptied?.dropped?.map { it.item.name })
        assertEquals(above.side(Face.UP), emptied?.dropAt)
        assertEquals(0, level)
    }

    @Test
    fun `seeds raise a composter about three times in ten, and are used up either way`() {
        world[above] = block("minecraft:composter")
        val random = Random(7)
        var raised = 0
        repeat(1000) {
            if (use("minecraft:wheat_seeds", random)?.sound?.sound == "block.composter.fill_success") raised++
            world[above] = block("minecraft:composter")
        }
        assertTrue(raised in 250..350)
        assertEquals("block.composter.fill", use("minecraft:oak_leaves", unlucky)?.sound?.sound)
    }

    @Test
    fun `hoppers fill a composter from above and take its bone meal from below`() {
        world[above] = block("minecraft:composter")
        val top = above.side(Face.UP)
        world[top] = blocks.stateOf("minecraft:hopper", mapOf("facing_direction" to Face.DOWN.ordinal, "toggle_bit" to 0.toByte()))
        world.hopperAt(top)!!.slots[0] = SlotStack(item("minecraft:pumpkin_pie").copy(count = 7), world.newStackId())
        val fuel = Fuel(data.recipes, items.of("minecraft:bucket"))

        val sounds = mutableListOf<BlockSound>()
        repeat(7 * HOPPER_COOLDOWN) { world.tickMachines(data.recipes, fuel, sounds = sounds, items = items) }
        assertEquals(7, level)
        assertTrue(sounds.all { it.sound == "block.composter.fill_success" })
        repeat(COMPOST_TICKS) { world.tickBlocks() }

        world[ground] = blocks.stateOf("minecraft:hopper", mapOf("facing_direction" to Face.DOWN.ordinal, "toggle_bit" to 0.toByte()))
        repeat(HOPPER_COOLDOWN) { world.tickMachines(data.recipes, fuel, items = items) }
        assertEquals(0, level)
        assertEquals("minecraft:bone_meal", world.hopperAt(ground)!!.slots.contents().filterNotNull().single().stack.item.name)
    }

    @Test
    fun `a ready composter drops its bone meal as it breaks`() {
        world[above] = blocks.stateOf("minecraft:composter", mapOf("composter_fill_level" to 8))
        val drops = Drops(data.items, data.creative, blocks)
        assertEquals(listOf("minecraft:composter", "minecraft:bone_meal"), drops.of(world.stateAt(above)!!).map { it.item.name })
    }

    private companion object {
        val data: VanillaData get() = vanillaData
    }
}
