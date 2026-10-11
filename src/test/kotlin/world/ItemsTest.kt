package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeCategory
import org.koremc.registry.CreativeGroup
import org.koremc.registry.CreativeInventory
import org.koremc.registry.CreativeItem
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemsTest {
    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val dirt = state("minecraft:dirt")
    private val grass = state("minecraft:grass_block")
    private val glass = state("minecraft:glass")
    private val slab = state("minecraft:oak_slab", "minecraft:vertical_half" to "bottom")
    private val doubleSlab = state("minecraft:oak_double_slab", "minecraft:vertical_half" to "bottom")
    private val door = state("minecraft:wooden_door", "upper_block_bit" to 1.toByte())
    private val palette = BlockPalette(listOf(air, stone, dirt, grass, glass, slab, doubleSlab, door))

    private val dirtItem = ItemDefinition("minecraft:dirt", 3, componentBased = false, ItemVersion.NONE)
    private val slabItem = ItemDefinition("minecraft:oak_slab", 158, componentBased = false, ItemVersion.NONE)
    private val cobblestone = ItemDefinition("minecraft:cobblestone", 4, componentBased = false, ItemVersion.NONE)
    private val drops = Drops(
        listOf(dirtItem, slabItem, cobblestone),
        CreativeInventory(listOf(CreativeGroup(CreativeCategory.NATURE, "", icon = null)), listOf(CreativeItem(ItemStack(dirtItem, block = dirt), group = 0))),
        palette,
    )

    // One layer of stone at the bottom of the world: its top is at y -63.
    private val world = FlatWorld(palette, listOf(stone))
    private val floor = MIN_Y + 1f

    @Test
    fun `items fall, land on the block below and stay put`() {
        val item = world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor + 3, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 10)

        repeat(40) { world.tickItems() }

        assertEquals(Vec3(0.5f, floor, 0.5f), item.position)
        assertTrue(item.onGround)
        assertEquals(0, item.pickupDelay)
        world.tickItems()
        assertFalse(item.moved) // Lying still, it has nothing to tell anyone
    }

    @Test
    fun `items slide to a stop on the ground, and land on slabs halfway up`() {
        val sliding = world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0.3f, 0f, 0f), pickupDelay = 0)
        repeat(20) { world.tickItems() }
        assertTrue(sliding.position.x in 1f..1.5f) // Ground friction stops it within a block
        assertEquals(0f, sliding.velocity.x, 0.001f)

        world[BlockPos(5, MIN_Y + 1, 5)] = slab
        val onSlab = world.spawnItem(ItemStack(dirtItem), Vec3(5.5f, floor + 2, 5.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        repeat(40) { world.tickItems() }
        assertEquals(floor + 0.5f, onSlab.position.y)
    }

    @Test
    fun `items stop against walls, and fall when the block under them goes`() {
        world[BlockPos(2, MIN_Y + 1, 0)] = stone
        val item = world.spawnItem(ItemStack(dirtItem), Vec3(1.5f, floor, 0.5f), Vec3(1f, 0f, 0f), pickupDelay = 0)
        world.tickItems()
        assertEquals(2f - 0.125f, item.position.x) // Its side against the wall

        world[BlockPos(1, MIN_Y, 0)] = air
        repeat(10) { world.tickItems() }
        assertTrue(item.position.y < floor)
    }

    @Test
    fun `items go after five minutes`() {
        world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)

        repeat(5999) { world.tickItems() }
        assertEquals(1, world.items.size)
        world.tickItems()
        assertEquals(0, world.items.size)
    }

    @Test
    fun `blocks drop their own item, or another, or nothing`() {
        assertEquals(listOf(ItemStack(dirtItem, block = dirt)), drops.of(dirt)) // With the block state the creative inventory gives it
        assertEquals(listOf(ItemStack(dirtItem, block = dirt)), drops.of(grass))
        assertEquals(listOf(ItemStack(cobblestone)), drops.of(stone))
        assertEquals(listOf(ItemStack(slabItem, count = 2, block = palette.defaultOf("minecraft:oak_slab"))), drops.of(doubleSlab))
        assertEquals(emptyList(), drops.of(glass))
        assertEquals(emptyList(), drops.of(door)) // The top half; the bottom half drops the door
    }

    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), 18168865)
}
