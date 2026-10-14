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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saplings growing into trees, and mushrooms and fungi growing huge, by the game's own blocks. */
class TreesTest {
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
        world.time = 6000 // Noon
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

    private fun grow(pos: BlockPos, seed: Int = 1) = world.growTree(pos, world.stateAt(pos)!!, Random(seed))

    @Test
    fun `every sapling grows a tree of its own wood and leaves, turning the grass under it to dirt`() {
        for ((x, kind) in listOf("oak", "birch", "spruce", "jungle", "acacia", "cherry", "poplar").withIndex()) {
            val at = BlockPos(x * 40, above.y, 0)
            put(at, "minecraft:${kind}_sapling")
            val grown = assertNotNull(grow(at), "$kind didn't grow")
            assertEquals("minecraft:${kind}_log", nameAt(at), kind)
            assertTrue(grown.any { world.stateAt(it)?.isLeaves == true }, "$kind has no leaves")
            assertEquals("minecraft:dirt", nameAt(at.side(Face.DOWN)), kind)
        }
    }

    @Test
    fun `a tree with no room doesn't grow, and leaves its sapling as it was`() {
        put(above, "minecraft:birch_sapling")
        put(BlockPos(0, above.y + 3, 0), "minecraft:stone")
        assertNull(grow(above))
        assertEquals("minecraft:birch_sapling", nameAt(above))
    }

    @Test
    fun `dark oaks and pale oaks grow only from four saplings in a square, and spruces and jungle trees grow huge from four`() {
        put(above, "minecraft:dark_oak_sapling")
        assertFalse(world.canGrowTree(above, world.stateAt(above)!!))
        assertNull(grow(above))

        for ((x, kind) in listOf("dark_oak", "pale_oak", "spruce", "jungle").withIndex()) {
            val corner = BlockPos(x * 40, above.y, 20)
            for (dx in 0..1) for (dz in 0..1) put(BlockPos(corner.x + dx, corner.y, corner.z + dz), "minecraft:${kind}_sapling")
            assertNotNull(grow(BlockPos(corner.x + 1, corner.y, corner.z + 1)), kind) // From any of the four
            for (dx in 0..1) for (dz in 0..1) assertEquals("minecraft:${kind}_log", nameAt(BlockPos(corner.x + dx, corner.y + 1, corner.z + dz)), kind)
        }
    }

    @Test
    fun `a sapling in daylight grows older, and then into its tree, on random ticks`() {
        put(above, "minecraft:oak_sapling")
        val growing = Growing(Random(1))
        repeat(300) { world.stateAt(above)?.takeIf { it.isSapling }?.let { world.randomTick(above, it, growing) } }
        assertEquals("minecraft:oak_log", nameAt(above))
    }

    @Test
    fun `bone meal grows a sapling, an azalea and a mangrove propagule, but not a dark oak sapling on its own`() {
        fun fertilize(at: BlockPos) {
            for (seed in 1..50) {
                if (world.stateAt(at)?.let { it.isSapling || it.name.endsWith("azalea") || it.name == MANGROVE_PROPAGULE } != true) return
                world.fertilize(at, world.stateAt(at)!!, items, Random(seed))
            }
        }
        put(above, "minecraft:cherry_sapling")
        fertilize(above)
        assertEquals("minecraft:cherry_log", nameAt(above))

        val azalea = BlockPos(40, above.y, 0)
        put(azalea, "minecraft:flowering_azalea")
        fertilize(azalea)
        assertEquals("minecraft:oak_log", nameAt(azalea))
        assertEquals(ROOTED_DIRT, nameAt(azalea.side(Face.DOWN)))

        val propagule = BlockPos(80, above.y, 0)
        put(propagule.side(Face.DOWN), MUD)
        put(propagule, MANGROVE_PROPAGULE)
        fertilize(propagule)
        assertTrue((propagule.y..propagule.y + 10).any { nameAt(BlockPos(propagule.x, it, propagule.z)) == "minecraft:mangrove_log" })
        assertTrue(nameAt(propagule) != MANGROVE_PROPAGULE)

        val lonely = BlockPos(120, above.y, 0)
        put(lonely, "minecraft:pale_oak_sapling")
        assertNull(world.fertilize(lonely, world.stateAt(lonely)!!, items, Random(1)))
    }

    @Test
    fun `woken by something changing beside them, a grown tree's leaves all find their wood, out to the furthest`() {
        for ((x, kind) in listOf("oak", "spruce", "jungle", "acacia", "dark_oak", "poplar").withIndex()) {
            for (seed in 1..20) {
                val corner = BlockPos(x * 40, above.y, seed * 40)
                for (dx in 0..1) for (dz in 0..1) put(BlockPos(corner.x + dx, corner.y, corner.z + dz), "minecraft:${kind}_sapling")
                val grown = grow(corner, seed) ?: continue
                val leaves = grown.filter { world.stateAt(it)?.isLeaves == true }
                for (leaf in leaves) world[leaf] = blocks.stateOf(nameAt(leaf)!!, world.stateAt(leaf)!!.properties + (LEAF_UPDATE to 1.toByte()))
                val growing = Growing(Random(seed))
                for (leaf in leaves) world.randomTick(leaf, world.stateAt(leaf)!!, growing)
                assertEquals(emptySet(), growing.broken.keys, "$kind, seed $seed")
            }
        }
    }

    @Test
    fun `a grown tree's leaves stay, till its trunk's cut down, when they decay`() {
        put(above, "minecraft:oak_sapling")
        val grown = grow(above, seed = 2)!!
        val leaves = grown.filter { world.stateAt(it)?.isLeaves == true }
        assertTrue(leaves.all { world.stateAt(it)?.properties?.get(LEAF_UPDATE) == 0.toByte() })

        // Each leaf that decays wakes those beside it, so they go from the trunk outward.
        val logs = grown.filter { nameAt(it) == "minecraft:oak_log" }
        for (log in logs) world[log] = blocks.stateOf(AIR)
        world.settle(logs)
        world.rules.set("randomTickSpeed", 4096)
        repeat(40) { world.tickRandomly(listOf(ChunkPos(0, 0)), Random(it)) }
        assertTrue(leaves.none { world.stateAt(it)?.isLeaves == true })
    }

    @Test
    fun `bone meal grows mushrooms on dirt into huge ones, a brown cap or a red dome on a stem`() {
        for ((x, colour) in listOf("brown", "red").withIndex()) {
            val at = BlockPos(x * 20, above.y, 40)
            put(at, "minecraft:${colour}_mushroom")
            val grown = assertNotNull(world.growHugeMushroom(at, world.stateAt(at)!!, Random(1)), colour)
            assertEquals("minecraft:mushroom_stem", nameAt(at))
            assertTrue(grown.any { nameAt(it) == "minecraft:${colour}_mushroom_block" }, colour)
        }
        // Not on stone.
        val onStone = BlockPos(60, above.y, 40)
        put(onStone.side(Face.DOWN), "minecraft:stone")
        put(onStone, "minecraft:brown_mushroom")
        assertNull(world.growHugeMushroom(onStone, world.stateAt(onStone)!!, Random(1)))
    }

    @Test
    fun `a fungus on its own nylium grows huge, and nylium sprouts roots and fungi with bone meal`() {
        val at = BlockPos(0, above.y, 60)
        put(at.side(Face.DOWN), CRIMSON_NYLIUM)
        put(at, CRIMSON_FUNGUS)
        val grown = assertNotNull(world.growHugeFungus(at, world.stateAt(at)!!, Random(1)))
        assertEquals("minecraft:crimson_stem", nameAt(at))
        assertTrue(grown.any { nameAt(it) == "minecraft:nether_wart_block" })

        val warped = BlockPos(20, above.y, 60)
        put(warped, WARPED_FUNGUS)
        put(warped.side(Face.DOWN), CRIMSON_NYLIUM)
        assertNull(world.growHugeFungus(warped, world.stateAt(warped)!!, Random(1))) // On the wrong nylium

        for (x in -2..2) for (z in -2..2) put(BlockPos(40 + x, ground.y, 60 + z), WARPED_NYLIUM)
        val used = world.fertilize(BlockPos(40, ground.y, 60), world.stateAt(BlockPos(40, ground.y, 60))!!, items, Random(1))!!
        assertTrue(used.changed.isNotEmpty())

        val netherrack = BlockPos(50, ground.y, 60)
        put(netherrack, NETHERRACK)
        put(netherrack.side(Face.EAST), WARPED_NYLIUM)
        world.fertilize(netherrack, world.stateAt(netherrack)!!, items, Random(1))
        assertEquals(WARPED_NYLIUM, nameAt(netherrack))
    }

    @Test
    fun `mushrooms stay and spread in the dark or on mycelium, but not in daylight on grass`() {
        put(above, "minecraft:brown_mushroom")
        assertFalse(world.survives(above, world.stateAt(above)!!))
        put(above.side(Face.DOWN), MYCELIUM)
        assertTrue(world.survives(above, world.stateAt(above)!!))
        for (x in -3..3) for (z in -3..3) put(BlockPos(x, ground.y, z), MYCELIUM)
        val growing = Growing(Random(1))
        repeat(500) { world.randomTick(above, world.stateAt(above)!!, growing) }
        assertTrue(growing.changed.any { nameAt(it) == "minecraft:brown_mushroom" })
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
