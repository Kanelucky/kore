package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.vanillaData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Filling and cloning blocks, as commands do, in a world of the game's own blocks. */
class EditingTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")), traits = data.traits)
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    /** A block in the free space above the world's one layer of stone. */
    private fun at(x: Int, y: Int = 0, z: Int = 0) = BlockPos(x, MIN_Y + 1 + y, z)

    private fun nameAt(pos: BlockPos) = world.stateAt(pos)?.name

    private val diamond get() = items.of("minecraft:diamond")!!

    /** Puts a chest at [pos] with a diamond in it. */
    private fun chestWithDiamond(pos: BlockPos) {
        world[pos] = block("minecraft:chest")
        world.containerAt(pos)[0] = SlotStack(diamond, world.newStackId())
    }

    private fun holdsDiamond(pos: BlockPos) = world.containerAt(pos)[0]?.stack?.item?.name == "minecraft:diamond"

    @Test
    fun `a fill puts the block all through the box, counting only the blocks it changed`() {
        val box = BlockBox(at(2, 1, 2), at(0))
        world[at(1)] = block("minecraft:dirt")

        assertEquals(17, world.fill(box, block("minecraft:dirt")).count)
        assertTrue(box.positions().all { nameAt(it) == "minecraft:dirt" })
        assertEquals(0, world.fill(box, block("minecraft:dirt")).count)
    }

    @Test
    fun `however big the fill, what stands at its far side takes it in, and walls within it join up`() {
        val box = BlockBox(at(0), at(39, 9, 39))
        world.fill(box, block("minecraft:stone"))
        val torch = at(39, 10, 39)
        world[torch] = blocks.stateOf("minecraft:torch", mapOf("torch_facing_direction" to "top"))

        world.fill(box, block(AIR))
        world.tickBlocks()
        assertEquals(AIR, nameAt(torch))

        val wall = block("minecraft:cobblestone_wall")
        world.fill(BlockBox(at(0), at(2, 2, 2)), wall)
        assertNotEquals(wall, world.stateAt(at(1, 1, 1)))
    }

    @Test
    fun `an outline fill covers the outside, a hollow one empties the inside too, and keep fills only air`() {
        val cube = BlockBox(at(0), at(2, 2, 2))
        val middle = at(1, 1, 1)
        world[middle] = block("minecraft:glass")

        world.fill(cube, block("minecraft:dirt"), FillMode.OUTLINE)
        assertEquals("minecraft:glass", nameAt(middle))
        assertEquals("minecraft:dirt", nameAt(at(0, 1, 1)))
        world.fill(cube, block("minecraft:gold_block"), FillMode.HOLLOW)
        assertEquals(AIR, nameAt(middle))
        assertEquals("minecraft:gold_block", nameAt(at(2, 2, 2)))
        assertEquals(1, world.fill(cube, block("minecraft:glass"), FillMode.KEEP).count)
        assertEquals("minecraft:glass", nameAt(middle))
    }

    @Test
    fun `a fill told what to replace replaces only that`() {
        world[at(0)] = block("minecraft:dirt")
        world[at(1)] = block("minecraft:glass")

        val edited = world.fill(BlockBox(at(0), at(2)), block("minecraft:gold_block")) { it.name == "minecraft:dirt" }
        assertEquals(1, edited.count)
        assertEquals(listOf("minecraft:gold_block", "minecraft:glass", AIR), (0..2).map { nameAt(at(it)) })
    }

    @Test
    fun `destroying breaks what was there first, with what it held, while replacing takes it away, even with a block of the same kind`() {
        val chest = at(0)
        chestWithDiamond(chest)
        val destroyed = world.fill(BlockBox(chest, chest), block("minecraft:stone"), FillMode.DESTROY)
        assertEquals(listOf("minecraft:chest"), destroyed.changes.broken.values.map { it.state.name })
        assertEquals(listOf(diamond), destroyed.changes.broken.values.single().contents.filterNotNull())

        chestWithDiamond(chest)
        val turned = blocks.states.first { it.name == "minecraft:chest" && it != block("minecraft:chest") }
        val replaced = world.fill(BlockBox(chest, chest), turned)
        assertTrue(replaced.changes.broken.isEmpty())
        assertEquals(turned, world.stateAt(chest))
        assertFalse(holdsDiamond(chest))
    }

    @Test
    fun `a clone copies blocks with what they keep, and moving them leaves air behind`() {
        world[at(0)] = block("minecraft:dirt")
        chestWithDiamond(at(1))
        val source = BlockBox(at(0), at(1))

        assertEquals(2, world.clone(source, at(5), move = false, items).count)
        assertEquals(listOf("minecraft:dirt", "minecraft:chest"), listOf(nameAt(at(5)), nameAt(at(6))))
        assertTrue(holdsDiamond(at(6)))
        assertTrue(holdsDiamond(at(1)), "the copy is a copy")

        world.clone(source, at(0, 0, 5), move = true, items)
        assertEquals(listOf(AIR, AIR), listOf(nameAt(at(0)), nameAt(at(1))))
        assertEquals("minecraft:dirt", nameAt(at(0, 0, 5)))
        assertTrue(holdsDiamond(at(1, 0, 5)))
    }

    @Test
    fun `a masked clone leaves the air out, a filtered one all but its block, and the copy is taken before it lands`() {
        world[at(0)] = block("minecraft:dirt")
        world[at(2)] = block("minecraft:glass")
        world[at(6)] = block("minecraft:gold_block")
        val source = BlockBox(at(0), at(2))

        world.clone(source, at(5), move = false, items) { it.name != AIR }
        assertEquals(listOf("minecraft:dirt", "minecraft:gold_block", "minecraft:glass"), (5..7).map { nameAt(at(it)) })
        world.clone(source, at(0, 0, 3), move = false, items) { it.name == "minecraft:glass" }
        assertEquals(listOf(AIR, AIR, "minecraft:glass"), (0..2).map { nameAt(at(it, 0, 3)) })
        world.clone(source, at(1), move = false, items)
        assertEquals(listOf("minecraft:dirt", "minecraft:dirt", AIR, "minecraft:glass"), (0..3).map { nameAt(at(it)) })
    }

    @Test
    fun `boxes know their size, whether they overlap, and where they'd be moved`() {
        val box = BlockBox(at(3, 2, 1), at(0))
        assertEquals(4L * 3 * 2, box.volume)
        assertEquals(at(0), box.min)
        assertTrue(box.overlaps(box.movedTo(at(3))))
        assertFalse(box.overlaps(box.movedTo(at(4))))
        assertFalse(BlockBox(at(0), BlockPos(0, MAX_Y + 1, 0)).inWorld)
    }

    private companion object {
        val data: VanillaData get() = vanillaData
    }
}
