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
    private val chest = state("minecraft:chest", "minecraft:cardinal_direction" to "south")
    private val carpet = state("minecraft:white_carpet")
    private val palette = BlockPalette(listOf(air, stone, dirt, grass, glass, slab, doubleSlab, door, chest, carpet))

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
    fun `items land on chests and carpets, rather than fall into them`() {
        world[BlockPos(5, MIN_Y + 1, 5)] = chest
        world[BlockPos(7, MIN_Y + 1, 5)] = carpet
        val onChest = world.spawnItem(ItemStack(dirtItem), Vec3(5.5f, floor + 2, 5.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        val onCarpet = world.spawnItem(ItemStack(dirtItem), Vec3(7.5f, floor + 2, 5.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        repeat(40) { world.tickItems() }
        assertEquals(floor + 0.875f, onChest.position.y)
        assertEquals(floor + 0.0625f, onCarpet.position.y)
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
    fun `items lying close together merge, the smaller stack into the larger`() {
        val small = world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        val large = world.spawnItem(ItemStack(dirtItem, count = 3), Vec3(1.2f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)

        // On the ground, they look for each other once a second.
        repeat(19) { world.tickItems() }
        assertEquals(2, world.items.size)
        world.tickItems()

        assertEquals(listOf(large), world.items.toList())
        assertEquals(4, large.stack.count)
        assertTrue(large.restacked)
        world.tickItems()
        assertFalse(large.restacked)
        assertEquals(null, world.itemWith(small.id))
    }

    @Test
    fun `of two stacks the same size, the first to look goes into the other, which waits as long as either to be picked up`() {
        world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 100)
        val second = world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 1f), Vec3(0f, 0f, 0f), pickupDelay = 0)

        repeat(20) { world.tickItems() }

        assertEquals(listOf(second), world.items.toList())
        assertEquals(2, second.stack.count)
        assertEquals(79, second.pickupDelay) // What the first had left, counted down as the second ticks after it
    }

    @Test
    fun `items in the air pass by those on the ground, and merge with them once they land`() {
        val lying = world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        repeat(18) { world.tickItems() }

        // Falling past it as it looks for others, a second after it was dropped.
        world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor + 0.24f, 0.8f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        repeat(2) { world.tickItems() }
        assertEquals(2, world.items.size)

        // It lands in its fourth tick, and looks for others itself a second after it was dropped.
        repeat(17) { world.tickItems() }
        assertEquals(2, world.items.size)
        world.tickItems()
        assertEquals(listOf(lying), world.items.toList())
        assertEquals(2, lying.stack.count)
    }

    @Test
    fun `items too far apart, of different kinds, or too many for one stack don't merge`() {
        world.spawnItem(ItemStack(dirtItem), Vec3(0.5f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        world.spawnItem(ItemStack(dirtItem), Vec3(1.3f, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0) // Their sides are 0.55 apart
        world.spawnItem(ItemStack(cobblestone), Vec3(0.5f, floor, 1f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        world.spawnItem(ItemStack(dirtItem, count = 60), Vec3(5.5f, floor, 5.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        world.spawnItem(ItemStack(dirtItem, count = 5), Vec3(5.5f, floor, 6f), Vec3(0f, 0f, 0f), pickupDelay = 0)

        repeat(80) { world.tickItems() }

        assertEquals(5, world.items.size)
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

    // A world with water, lava and fire in it, on the same floor.
    private val liquids = listOf(WATER, FLOWING_WATER, LAVA, FLOWING_LAVA).flatMap { name -> (0..15).map { state(name, "liquid_depth" to it) } }
    private val fire = state(FIRE, "age" to 0)
    private val soulFire = state(SOUL_FIRE, "age" to 0)
    private val wild = FlatWorld(BlockPalette(listOf(air, stone, fire, soulFire) + liquids), listOf(stone))
    private val netherite = ItemDefinition("minecraft:netherite_ingot", 610, componentBased = false, ItemVersion.NONE)

    private fun water(depth: Int) = wild.liquidState(Liquid.WATER, depth)
    private fun lava(depth: Int) = wild.liquidState(Liquid.LAVA, depth)
    private fun on(x: Int, y: Int = 0) = BlockPos(x, MIN_Y + 1 + y, 0)
    private fun drop(x: Float, item: ItemDefinition = dirtItem) = wild.spawnItem(ItemStack(item), Vec3(x, floor, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)

    @Test
    fun `items float up through water to its top, and bob there`() {
        wild[on(0)] = water(0)
        wild[on(0, y = 1)] = water(0)
        val item = drop(0.5f)

        repeat(40) { wild.tickItems() }
        assertTrue(item.position.y > floor + 0.2f, "it rises")
        val heights = List(400) { wild.tickItems(); item.position.y }.drop(200)
        val top = floor + 1 + 8 / 9f
        assertTrue(heights.all { it in top - 0.5f..top }, "and stays just under the top: $heights")
        assertTrue(heights.distinct().size > 1, "bobbing")
        assertEquals(0.5f, item.position.x, "still water leaves it where it is")
    }

    @Test
    fun `flowing water carries items along the way it flows`() {
        (0..7).forEach { wild[on(it)] = water(it) }
        val item = drop(2.5f)

        repeat(20) { wild.tickItems() }
        assertTrue(item.position.x > 4f, "downstream: ${item.position}")
        assertEquals(0.5f, item.position.z)
    }

    @Test
    fun `lava burns items up, fizzing`() {
        wild[on(0)] = lava(0)
        drop(0.5f)

        assertEquals(1, wild.tickItems().size)
        assertEquals(1, wild.items.size)
        assertEquals(1, wild.tickItems().size)
        assertEquals(0, wild.items.size)
    }

    @Test
    fun `fire burns items up, soul fire faster, and netherite comes through both and lava`() {
        wild[on(0)] = fire
        wild[on(2)] = soulFire
        wild[on(4)] = lava(0)
        val inFire = drop(0.5f)
        drop(2.5f)
        val fireproof = listOf(0.5f, 2.5f, 4.5f).map { drop(it, netherite) }

        repeat(2) { wild.tickItems() }
        assertEquals(fireproof.toSet() + inFire, wild.items.toSet())
        wild.tickItems()
        assertEquals(fireproof.toSet(), wild.items.toSet())
        assertTrue(List(100) { wild.tickItems() }.all { it.isEmpty() }, "with no fizz")
        assertEquals(fireproof.toSet(), wild.items.toSet())
    }

    @Test
    fun `items burn on once out of fire, until they're gone, or water puts them out`() {
        wild[on(0)] = fire
        wild[on(2)] = fire
        drop(0.5f)
        val doused = drop(2.5f)

        wild.tickItems()
        wild[on(0)] = air
        wild[on(2)] = water(0)
        repeat(30) { wild.tickItems() }
        assertEquals(2, wild.items.size, "burning harms them only once a second")
        repeat(70) { wild.tickItems() }
        assertEquals(listOf(doused), wild.items.toList())
    }

    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), 18168865)
}
