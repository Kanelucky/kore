package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.inventory.SlotStack
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.vanillaData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the game's own blocks pick as, a middle click on them, and what picking them with their data keeps. */
class PickingTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    private val ground = BlockPos(0, MIN_Y + 1, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf("minecraft:stone", "minecraft:dirt").map(::block), traits = data.traits)
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    private fun pickedName(name: String, blockEntity: NbtMap? = null) = pickedItem(block(name), blockEntity, items)?.item?.name

    @Test
    fun `blocks pick as the item that places them`() {
        assertEquals("minecraft:stone", pickedName("minecraft:stone"))
        assertEquals("minecraft:wheat_seeds", pickedName("minecraft:wheat"))
        assertEquals("minecraft:oak_sign", pickedName("minecraft:wall_sign"))
        assertEquals("minecraft:oak_slab", pickedName("minecraft:oak_double_slab"))
        assertEquals("minecraft:furnace", pickedName("minecraft:lit_furnace"))
        assertEquals("minecraft:redstone_lamp", pickedName("minecraft:lit_redstone_lamp"))
        assertEquals("minecraft:piston", pickedName("minecraft:piston_arm_collision"))
    }

    @Test
    fun `air, liquids and fire pick as nothing`() {
        for (name in listOf("minecraft:air", "minecraft:water", "minecraft:lava", "minecraft:fire")) assertNull(pickedName(name), name)
    }

    @Test
    fun `a bed picks in its colour`() {
        val red = NbtMap.builder().putString("id", "Bed").putByte("color", 14).build()
        val picked = pickedItem(block("minecraft:bed"), red, items)!!
        assertEquals("minecraft:bed", picked.item.name)
        assertEquals(14, picked.aux)
    }

    @Test
    fun `a chest picked with its data keeps its items, but not where it was or what it paired with, and places with them`() {
        world[above] = block("minecraft:chest")
        world[above.side(Face.EAST)] = block("minecraft:chest")
        world.setBlockEntity(above, NbtMap.builder().putString("id", "Chest").putInt("pairx", above.x + 1).putInt("pairz", above.z).build())
        world.containerAt(above)[3] = SlotStack(items.of("minecraft:diamond", 5)!!, 1)

        val picked = world.withBlockData(pickedItem(world.stateAt(above)!!, world.blockEntityAt(above), items)!!, above)!!
        val kept = picked.nbt.getCompound("BlockEntityTag")
        assertTrue("Items" in kept)
        for (key in listOf("x", "y", "z", "pairx", "pairz")) assertFalse(key in kept, key)
        assertEquals(listOf("+(DATA)"), picked.nbt.getCompound("display").getList("Lore", NbtType.STRING))

        val placed = BlockPos(5, above.y, 5)
        world[placed] = block("minecraft:chest")
        assertTrue(world.restoreBlockData(placed, picked, items))
        assertEquals("minecraft:diamond", world.containerAt(placed)[3]?.stack?.item?.name)
        assertEquals(5, world.containerAt(placed)[3]?.stack?.count)
        val saved = world.blockEntityAt(placed)!!
        assertEquals(placed.x, saved.getInt("x"))
        assertFalse("pairx" in saved)
    }

    @Test
    fun `a block that keeps nothing picks with no data, and its item places none`() {
        world[above] = block("minecraft:stone")
        assertNull(world.withBlockData(items.of("minecraft:stone")!!, above))
        assertFalse(world.restoreBlockData(above, items.of("minecraft:stone")!!, items))
    }

    private companion object {
        val data: VanillaData get() = vanillaData
    }
}
