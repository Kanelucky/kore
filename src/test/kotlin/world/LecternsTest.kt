package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LecternsTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val stone = state("minecraft:stone")

    /** A lectern facing south, giving power if [powered]. */
    private fun lectern(powered: Boolean = false) = state(LECTERN, CARDINAL to "south", "powered_bit" to bit(powered))
    private val wire = (0..MAX_POWER).map { state(REDSTONE_WIRE, REDSTONE_SIGNAL to it) }
    private val palette = BlockPalette(listOf(state(AIR), stone, lectern(), lectern(powered = true)) + wire)

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(0, -63, 0)

    private fun item(name: String, id: Int) = ItemDefinition(name, id, componentBased = false, ItemVersion.NONE, maxStackSize = 1)
    private val writable = item(WRITABLE_BOOK, 1)
    private val written = item(WRITTEN_BOOK, 2)
    private val plainBook = item("minecraft:book", 3)

    /** A book with [pages] written in it. */
    private fun book(pages: Int, kind: ItemDefinition = written): ItemStack {
        val written = (1..pages).map { NbtMap.builder().putString("text", "Page $it").putString("photoname", "").build() }
        return ItemStack(kind, nbt = NbtMap.builder().putList(PAGES, NbtType.COMPOUND, written).build())
    }

    private fun tick(times: Int = 1) = repeat(times) {
        world.tickBlocks()
        world.updatePower()
    }

    @Test
    fun `a book and quill or a written book goes on an empty lectern, open at its first page, and nothing else does`() {
        world[at] = lectern()
        assertFalse(world.putOnLectern(at, ItemStack(plainBook)))
        assertTrue(world.putOnLectern(at, book(4, writable)))
        assertFalse(world.putOnLectern(at, book(2)))

        val data = world.blockEntityAt(at)!!
        assertEquals("Lectern", data.getString("id"))
        assertEquals(listOf(1, 0, 4), listOf(data.getByte("hasBook").toInt(), data.getInt("page"), data.getInt("totalPages")))
        assertEquals(WRITABLE_BOOK, data.getCompound("book").getString("Name"))
    }

    @Test
    fun `turning a page pulses redstone from the lectern for two ticks`() {
        world[at] = lectern()
        val dust = BlockPos(1, -63, 0)
        world[dust] = wire[0]
        world.putOnLectern(at, book(10))
        world.updatePower()

        assertTrue(world.turnPage(at, 2, 10))
        world.updatePower()
        assertEquals(lectern(powered = true), world.stateAt(at))
        assertEquals(MAX_POWER, world.stateAt(dust)?.properties?.get(REDSTONE_SIGNAL))
        assertEquals(2, world.blockEntityAt(at)?.getInt("page"))
        tick(2)
        assertEquals(lectern(), world.stateAt(at))
        assertEquals(0, world.stateAt(dust)?.properties?.get(REDSTONE_SIGNAL))
    }

    @Test
    fun `a comparator reads more the further through a lectern's book the reader is, as Bedrock reckons it`() {
        world[at] = lectern()
        assertEquals(0, world.fullness(at))
        world.putOnLectern(at, book(9))
        // Pages 1 and 2 open, of the eight that make pairs.
        assertEquals(4, world.fullness(at))
        world.turnPage(at, 6, 9)
        assertEquals(MAX_POWER, world.fullness(at))
        // The last page alone reads as the pair before it.
        world.turnPage(at, 8, 9)
        assertEquals(MAX_POWER, world.fullness(at))

        // Three pages are too few to turn.
        world.takeFromLectern(at)
        world.putOnLectern(at, book(3))
        assertEquals(MAX_POWER, world.fullness(at))
    }

    @Test
    fun `punching a lectern knocks its book off on top, out of its front, and breaking it drops the book`() {
        world[at] = lectern()
        world.putOnLectern(at, book(2))
        assertTrue(world.knockBookOff(at))
        assertNull(world.lecternBook(at))
        assertEquals(0, world.blockEntityAt(at)?.getByte("hasBook")?.toInt())
        val dropped = world.items.single()
        assertEquals(WRITTEN_BOOK, dropped.stack.item.name)
        assertEquals(Vec3(0.5f, -62f, 0.75f), dropped.position)
        assertFalse(world.knockBookOff(at))

        world.putOnLectern(at, book(2))
        val broken = world.breakBlock(at).broken.getValue(at)
        assertEquals(listOf(WRITTEN_BOOK), broken.contents.filterNotNull().map { it.item.name })
    }
}
