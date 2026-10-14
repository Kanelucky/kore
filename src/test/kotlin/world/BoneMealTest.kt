package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bone meal, and picking what grows, on the game's own blocks. */
class BoneMealTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)
    private val twoUp = above.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")), traits = data.traits)
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String, vararg properties: Pair<String, Any>): BlockState {
        val default = blocks.defaultOf(name) ?: error("no block $name")
        return if (properties.isEmpty()) default else blocks.stateOf(name, default.properties + properties)
    }

    private fun put(pos: BlockPos, name: String, vararg properties: Pair<String, Any>) {
        world[pos] = block(name, *properties)
    }

    private fun nameAt(pos: BlockPos) = world.stateAt(pos)?.name

    private fun numberAt(pos: BlockPos, property: String) = world.stateAt(pos)?.properties?.get(property)

    private fun fertilize(pos: BlockPos, seed: Int = 1) = world.fertilize(pos, world.stateAt(pos)!!, items, Random(seed))

    @Test
    fun `crops grow two to five stages a bone meal, which is used up, with the particles of growing, and a grown crop takes none`() {
        put(above, "minecraft:farmland")
        put(twoUp, "minecraft:wheat")
        val used = fertilize(twoUp)!!
        assertTrue(numberAt(twoUp, GROWTH) as Int in 2..5)
        assertEquals(HeldAfter.USED_UP, used.held)
        assertEquals(listOf(WorldEvent(twoUp, 2005, 0, centred = false)), used.events)
        repeat(3) { fertilize(twoUp, it) }
        assertEquals(MAX_CROP_GROWTH, numberAt(twoUp, GROWTH))
        assertNull(fertilize(twoUp))
        assertNull(fertilize(ground)) // Stone grows nothing
    }

    @Test
    fun `bone meal on grass sprouts grass and flowers on the grass around it`() {
        for (x in -3..3) for (z in -3..3) put(BlockPos(x, above.y, z), "minecraft:grass_block")
        val used = fertilize(above)!!
        assertEquals(1, used.events.single().data) // Over an area
        val grown = used.changed.mapNotNull(::nameAt).toSet()
        assertTrue("minecraft:short_grass" in grown, "$grown")
        assertTrue(used.changed.all { nameAt(it.side(Face.DOWN)) == "minecraft:grass_block" || nameAt(it) == "minecraft:tall_grass" })
    }

    @Test
    fun `short grass grows two blocks tall, and tall flowers drop another`() {
        put(above, "minecraft:short_grass")
        fertilize(above)
        assertEquals(listOf("minecraft:tall_grass", "minecraft:tall_grass"), listOf(nameAt(above), nameAt(twoUp)))
        assertEquals(1.toByte(), world.stateAt(twoUp)?.properties?.get(UPPER_BLOCK))

        put(above, "minecraft:sunflower")
        assertEquals(listOf("minecraft:sunflower"), fertilize(above)!!.dropped.map { it.item.name })
    }

    @Test
    fun `sugar cane grows three tall at once, and berries a stage, and bamboo a block or two`() {
        put(above, "minecraft:sand")
        put(twoUp, REEDS)
        fertilize(twoUp)
        assertEquals(listOf(REEDS, REEDS, REEDS, AIR), (0..3).map { nameAt(BlockPos(0, twoUp.y + it, 0)) })

        val bush = BlockPos(5, twoUp.y, 0)
        put(bush, SWEET_BERRY_BUSH)
        fertilize(bush)
        assertEquals(1, numberAt(bush, GROWTH))

        val bamboo = BlockPos(10, above.y, 0)
        put(bamboo, BAMBOO)
        fertilize(bamboo)
        assertEquals(BAMBOO, nameAt(bamboo.side(Face.UP)))
    }

    @Test
    fun `a pitcher crop grows its top half at its fourth stage`() {
        put(above, "minecraft:farmland")
        put(twoUp, PITCHER_CROP, GROWTH to 2)
        fertilize(twoUp)
        val top = twoUp.side(Face.UP)
        assertEquals(PITCHER_TOP_GROWTH, numberAt(twoUp, GROWTH))
        assertEquals(PITCHER_CROP, nameAt(top))
        assertEquals(1.toByte(), world.stateAt(top)?.properties?.get(UPPER_BLOCK))
        fertilize(top) // By either half
        assertEquals(MAX_PITCHER_GROWTH, numberAt(twoUp, GROWTH))
        assertNull(fertilize(twoUp))
    }

    @Test
    fun `a small dripleaf grows into a big one, which bone meal makes taller`() {
        put(above, "minecraft:clay")
        put(twoUp, "minecraft:small_dripleaf_block", UPPER_BLOCK to 0.toByte())
        put(twoUp.side(Face.UP), "minecraft:small_dripleaf_block", UPPER_BLOCK to 1.toByte())
        fertilize(twoUp)
        val height = (0..5).takeWhile { nameAt(twoUp.side(Face.UP, it)) == BIG_DRIPLEAF }.size
        assertTrue(height in 2..5)
        assertEquals(1.toByte(), world.stateAt(twoUp.side(Face.UP, height - 1))?.properties?.get("big_dripleaf_head"))
        fertilize(twoUp)
        assertEquals(BIG_DRIPLEAF, nameAt(twoUp.side(Face.UP, height)))
        assertEquals(0.toByte(), world.stateAt(twoUp.side(Face.UP, height - 1))?.properties?.get("big_dripleaf_head"))
    }

    @Test
    fun `ripe berries are picked by hand, leaving the bush bare, and glow berries off cave vines, which bone meal grows back`() {
        put(above, SWEET_BERRY_BUSH, GROWTH to MAX_BERRY_GROWTH)
        val picked = world.pick(above, world.stateAt(above)!!, items, Random(1))!!
        assertTrue(picked.dropped.single().let { it.item.name == "minecraft:sweet_berries" && it.count in 2..3 })
        assertEquals(1, numberAt(above, GROWTH))
        assertNull(world.pick(above, world.stateAt(above)!!, items))

        val vines = BlockPos(5, above.y + 3, 0)
        put(vines.side(Face.UP), "minecraft:stone")
        put(vines, CAVE_VINES_BERRIES_HEAD)
        assertEquals("minecraft:glow_berries", world.pick(vines, world.stateAt(vines)!!, items)!!.dropped.single().item.name)
        assertEquals(CAVE_VINES, nameAt(vines))
        fertilize(vines)
        assertEquals(CAVE_VINES_BERRIES_HEAD, nameAt(vines))
    }

    /** The blocks [height] up from the ground, five each way across from the middle. */
    private fun layer(height: Int) = (-5..5).flatMap { x -> (-5..5).map { z -> BlockPos(x, MIN_Y + height, z) } }

    @Test
    fun `moss spreads a patch of itself over the stone around it, growing azaleas, grass and carpets, and pale moss its own`() {
        put(above, MOSS_BLOCK)
        assertTrue(fertilize(above)!!.changed.isNotEmpty())
        assertTrue(layer(0).count { nameAt(it) == MOSS_BLOCK } >= 4, "stone covered")
        val plants = setOf("minecraft:azalea", "minecraft:flowering_azalea", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:moss_carpet")
        assertTrue(layer(1).any { nameAt(it) in plants })

        startWorld()
        put(above, PALE_MOSS_BLOCK)
        fertilize(above, seed = 2)
        assertTrue(layer(0).count { nameAt(it) == PALE_MOSS_BLOCK } >= 4)
        assertTrue((layer(1) + layer(2)).none { nameAt(it).let { name -> name == "minecraft:azalea" || name == "minecraft:moss_carpet" } })
        put(above.side(Face.UP), "minecraft:stone")
        assertNull(fertilize(above), "nothing grows under a block")
    }

    @Test
    fun `glow lichen spreads by a face, over the wall it's on`() {
        for (z in -1..1) for (y in 1..3) put(BlockPos(0, MIN_Y + y, z), "minecraft:stone")
        val lichen = BlockPos(1, MIN_Y + 2, 0)
        put(lichen, "minecraft:glow_lichen", MULTI_FACE to MULTI_FACE_BITS.getValue(Face.WEST))
        fun faces() = layer(1).plus(layer(2)).plus(layer(3)).sumOf { at ->
            world.stateAt(at)?.takeIf { it.name == "minecraft:glow_lichen" }?.let { Integer.bitCount(it.properties[MULTI_FACE] as Int) } ?: 0
        }
        repeat(3) { fertilize(lichen, it) }
        assertEquals(4, faces())
    }

    @Test
    fun `seagrass in still water grows tall`() {
        for (height in 1..3) world[BlockPos(0, MIN_Y + height, 0)] = world.liquidState(Liquid.WATER, 0)
        put(above, "minecraft:seagrass")
        fertilize(above)
        assertEquals("double_bot", numberAt(above, "sea_grass_type"))
        assertEquals("double_top", numberAt(twoUp, "sea_grass_type"))
        assertNull(fertilize(above), "tall already")
    }

    @Test
    fun `sea pickles on living coral spread over the coral around them, growing to four, but not on stone`() {
        layer(1).forEach { put(it, "minecraft:tube_coral_block") }
        layer(2).plus(layer(3)).forEach { world[it] = world.liquidState(Liquid.WATER, 0) }
        val pickles = BlockPos(0, MIN_Y + 2, 0)
        put(pickles, "minecraft:sea_pickle")
        repeat(5) { fertilize(pickles, it) }
        assertEquals(3, numberAt(pickles, "cluster_count"))
        val spread = layer(2).filter { it != pickles && nameAt(it) == "minecraft:sea_pickle" }
        assertTrue(spread.isNotEmpty() && spread.all { abs(it.x) + abs(it.z) <= 2 })

        put(BlockPos(4, MIN_Y + 1, 4), "minecraft:stone")
        put(BlockPos(4, MIN_Y + 2, 4), "minecraft:sea_pickle")
        assertNull(fertilize(BlockPos(4, MIN_Y + 2, 4)))
    }

    @Test
    fun `bone meal on the seabed under two blocks of water grows seagrass around it, but not under one`() {
        layer(1).forEach { put(it, "minecraft:sand") }
        layer(2).plus(layer(3)).forEach { world[it] = world.liquidState(Liquid.WATER, 0) }
        assertTrue(world.fertilizeSeabed(above, Random(1))!!.changed.isNotEmpty())
        assertTrue(layer(2).any { nameAt(it) == "minecraft:seagrass" })

        layer(3).forEach { world[it] = block(AIR) }
        assertNull(world.fertilizeSeabed(BlockPos(3, MIN_Y + 1, 3), Random(1)))
        assertNull(world.fertilizeSeabed(ground, Random(1)), "stone isn't seabed")
    }

    @Test
    fun `a dispenser uses bone meal on what's in front of it, or clicks and keeps it if nothing grows`() {
        val at = BlockPos(0, MIN_Y + 2, 0)
        put(at, "minecraft:dispenser", FACING_DIRECTION to Face.EAST.ordinal)
        val dispenser = world.dispenserAt(at)!!
        dispenser.slots[0] = SlotStack(items.of(BONE_MEAL, 2)!!, 1)
        val fuel = Fuel(data.recipes, items.of("minecraft:bucket"))
        val front = at.side(Face.EAST)
        put(front.side(Face.DOWN), "minecraft:farmland")
        put(front, "minecraft:wheat")

        val fired = world.fire(at, dispenser, data.recipes, fuel, items)!!
        assertTrue(fired.dispensed)
        assertTrue(numberAt(front, GROWTH) as Int >= 2)
        assertEquals(listOf(front), fired.grew?.changed)
        assertEquals(1, dispenser.slots[0]?.stack?.count)

        put(front, "minecraft:stone")
        assertEquals(Fired(at, Face.EAST, dispensed = false), world.fire(at, dispenser, data.recipes, fuel, items))
        assertEquals(1, dispenser.slots[0]?.stack?.count, "kept")
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
