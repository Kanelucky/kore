package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
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

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
