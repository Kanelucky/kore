package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.dyedColour
import org.koremc.registry.gameData
import org.koremc.registry.withDyedColour
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cauldrons, by the game's own blocks, as Bedrock has them. */
class CauldronsTest {
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
        world.place(block("minecraft:cauldron"), ground, Face.UP, 0f, Placer(Face.NORTH))
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    private fun item(name: String, aux: Int = 0, count: Int = 1): ItemStack = items.of(name, count, aux) ?: error("no item $name")

    private fun use(held: ItemStack) = world.useCauldron(above, world.stateAt(above)!!, held, items)
    private fun use(name: String, aux: Int = 0) = use(item(name, aux))

    /** What's in the cauldron: what it holds, and how many levels of it. */
    private val inside get() = world.stateAt(above)!!.properties.let { it["cauldron_liquid"] to it["fill_level"] }
    private val kept get() = world.blockEntityAt(above)!!

    @Test
    fun `buckets fill a cauldron and take a full one's water back, swapped for the other bucket`() {
        val filled = use("minecraft:water_bucket")
        assertEquals("water" to 6, inside)
        assertEquals(HeldAfter.SWAPPED, filled?.held)
        assertEquals("minecraft:bucket", filled?.given?.item?.name)
        assertEquals(listOf(WorldEvent(above, 3506, 0)), filled?.events)

        assertEquals("minecraft:water_bucket", use("minecraft:bucket")?.given?.item?.name)
        assertEquals("water" to 0, inside)
        use("minecraft:potion")
        assertNull(use("minecraft:bucket")) // Only a full one
    }

    @Test
    fun `bottles take a third of the water out and put it back`() {
        use("minecraft:water_bucket")
        assertEquals("minecraft:potion" to 0, use("minecraft:glass_bottle")?.given?.let { it.item.name to it.aux })
        assertEquals("water" to 4, inside)
        assertEquals("minecraft:glass_bottle", use("minecraft:potion")?.given?.item?.name)
        assertEquals("water" to 6, inside)
        assertNull(use("minecraft:potion")) // Full
    }

    @Test
    fun `lava and powder snow fill a cauldron too, and anything else that goes in mixes, leaving it empty`() {
        use("minecraft:lava_bucket")
        assertEquals("lava" to 6, inside)
        assertEquals(3, world.fullness(above))
        val mixed = use("minecraft:water_bucket")
        assertEquals(listOf(WorldEvent(above, 3501, 0)), mixed?.events)
        assertEquals("minecraft:bucket", mixed?.given?.item?.name)
        assertEquals("water" to 0, inside)

        use("minecraft:powder_snow_bucket")
        assertEquals("powder_snow" to 6, inside)
        assertEquals("minecraft:powder_snow_bucket", use("minecraft:bucket")?.given?.item?.name)
        assertEquals("water" to 0, inside)
    }

    @Test
    fun `potions make a cauldron of potion, bottled back out the same, and a different one mixes`() {
        val nightVision = 5
        use("minecraft:splash_potion", nightVision)
        assertEquals("water" to 2, inside)
        assertEquals(nightVision.toShort(), kept.getShort("PotionId"))
        assertEquals(1.toShort(), kept.getShort("PotionType"))
        use("minecraft:splash_potion", nightVision)
        assertEquals("water" to 4, inside)

        assertEquals("minecraft:splash_potion" to nightVision, use("minecraft:glass_bottle")?.given?.let { it.item.name to it.aux })
        assertEquals("water" to 2, inside)

        assertEquals(listOf(WorldEvent(above, 3501, 0)), use("minecraft:potion", 6)?.events)
        assertEquals("water" to 0, inside)
        assertEquals((-1).toShort(), kept.getShort("PotionId"))
    }

    @Test
    fun `arrows dipped in a potion come out tipped with it`() {
        repeat(3) { use("minecraft:potion", 9) }
        val tipped = use(item("minecraft:arrow", count = 20))
        assertEquals(20, tipped?.count)
        assertEquals(Triple("minecraft:arrow", 10, 20), tipped?.given?.let { Triple(it.item.name, it.aux, it.count) })
        assertEquals("water" to 2, inside)

        // With little left, up to sixteen, which takes the rest.
        assertEquals(16, use(item("minecraft:arrow", count = 64))?.count)
        assertEquals("water" to 0, inside)
    }

    @Test
    fun `dyes colour the water, mixing, and leather dipped in it takes the colour, while plain water washes it out`() {
        use("minecraft:water_bucket")
        use("minecraft:red_dye")
        assertEquals(0xFFB02E26.toInt(), kept.getInt("CustomColor"))
        assertEquals(listOf(WorldEvent(above, 3508, 0)), use("minecraft:blue_dye")?.events)
        val purple = 0xFF763968.toInt() // Half of each
        assertEquals(purple, kept.getInt("CustomColor"))

        val dyed = use("minecraft:leather_helmet")
        assertEquals(purple, dyed?.given?.dyedColour)
        assertEquals(HeldAfter.CHANGED, dyed?.held) // In creative too
        assertEquals("water" to 5, inside)
        assertNull(use("minecraft:red_shulker_box")) // Not in dyed water

        // A whole set of armour leaves a third of the water.
        for (piece in listOf("minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots")) use(piece)
        assertEquals("water" to 2, inside)

        use("minecraft:water_bucket")
        assertEquals(false, kept.containsKey("CustomColor"))
        val washed = use(item("minecraft:leather_boots").withDyedColour(purple))
        assertNull(washed?.given?.dyedColour)
        assertEquals(listOf(WorldEvent(above, 3503, 0)), washed?.events)
        assertEquals("water" to 5, inside)
    }

    @Test
    fun `plain water washes a banner's top pattern off, and a shulker box's dye`() {
        use("minecraft:water_bucket")
        val pattern = { colour: Int -> NbtMap.builder().putString("Pattern", "bs").putInt("Color", colour).build() }
        val banner = item("minecraft:banner").copy(nbt = NbtMap.builder().putList("Patterns", NbtType.COMPOUND, listOf(pattern(1), pattern(2))).build())
        val washed = use(banner)?.given
        assertEquals(listOf(pattern(1)), washed?.nbt?.getList("Patterns", NbtType.COMPOUND))
        assertEquals(listOf(WorldEvent(above, 3509, 0)), use(washed!!)?.events)
        assertNull(use(item("minecraft:banner"))) // No patterns left to wash

        val box = item("minecraft:red_shulker_box").copy(nbt = NbtMap.builder().putString("CustomName", "Stuff").build())
        val plain = use(box)?.given
        assertEquals("minecraft:undyed_shulker_box", plain?.item?.name)
        assertEquals("Stuff", plain?.nbt?.getString("CustomName"))
        assertEquals("water" to 3, inside)
    }

    @Test
    fun `water touching a cauldron's top fills it, plain`() {
        use("minecraft:potion", 9)
        world[above.side(Face.UP)] = world.liquidState(Liquid.WATER, 0)
        val ticked = world.tickBlocks()
        assertEquals("water" to 6, inside)
        assertEquals((-1).toShort(), kept.getShort("PotionId"))
        assertEquals(listOf(WorldEvent(above, 3506, 0)), ticked.events.filter { it.pos == above })
    }

    @Test
    fun `a comparator reads a cauldron by its thirds`() {
        assertEquals(0, world.fullness(above))
        use("minecraft:potion")
        assertEquals(1, world.fullness(above))
        use("minecraft:potion")
        assertEquals(2, world.fullness(above))
        use("minecraft:potion")
        assertEquals(3, world.fullness(above))
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
