package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.EnchantmentLevel
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import org.koremc.registry.withEnchantments
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the game's own blocks drop, by the tool that breaks them. */
class DropsTest {
    private val blocks get() = data.blocks
    private lateinit var drops: Drops

    @BeforeTest
    fun loadDrops() {
        assumeGameData()
        drops = Drops(data.items, data.creative, blocks, Random(1))
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    private fun tool(name: String, vararg enchantments: Pair<Int, Int>): ItemStack =
        ItemStack(data.items.first { it.name == name }).withEnchantments(enchantments.map { (id, level) -> EnchantmentLevel(id, level) })

    private val pickaxe get() = tool("minecraft:diamond_pickaxe")
    private val silkTouch get() = tool("minecraft:diamond_pickaxe", SILK_TOUCH to 1)
    private val fortune get() = tool("minecraft:diamond_pickaxe", FORTUNE to 3)

    private fun dropOf(name: String, tool: ItemStack? = null) = drops.of(block(name), tool = tool).map { it.item.name }

    /** How many of a block's one drop each of [times] breaks gives. */
    private fun counts(name: String, tool: ItemStack?, times: Int = 300) = (1..times).map { drops.of(block(name), tool = tool).sumOf { it.count } }

    @Test
    fun `Silk Touch takes blocks whole, as they are, but not farmland, paths or grasses`() {
        assertEquals(listOf("minecraft:cobblestone"), dropOf("minecraft:stone", pickaxe))
        assertEquals(listOf("minecraft:stone"), dropOf("minecraft:stone", silkTouch))
        assertEquals(listOf("minecraft:grass_block"), dropOf("minecraft:grass_block", silkTouch))
        assertEquals(listOf("minecraft:glass"), dropOf("minecraft:glass", silkTouch))
        assertEquals(listOf("minecraft:diamond_ore"), dropOf("minecraft:diamond_ore", silkTouch))
        assertEquals(listOf("minecraft:redstone_ore"), dropOf("minecraft:lit_redstone_ore", silkTouch)) // Unlit
        assertEquals(listOf("minecraft:dirt"), dropOf("minecraft:farmland", silkTouch))
        assertTrue("minecraft:short_grass" !in dropOf("minecraft:short_grass", silkTouch))
    }

    @Test
    fun `shears take leaves, grasses, vines and cobwebs whole`() {
        val shears = tool("minecraft:shears")
        for (name in listOf("minecraft:oak_leaves", "minecraft:short_grass", "minecraft:vine", "minecraft:web")) {
            assertEquals(listOf(name), dropOf(name, shears))
        }
        assertEquals(emptyList(), dropOf("minecraft:web", pickaxe))
    }

    @Test
    fun `ores drop what's in them, Fortune multiplying it, or adding to redstone's`() {
        assertEquals(setOf(1), counts("minecraft:diamond_ore", pickaxe).toSet())
        assertEquals(listOf("minecraft:diamond"), dropOf("minecraft:diamond_ore", pickaxe))
        assertEquals(setOf(1, 2, 3, 4), counts("minecraft:diamond_ore", fortune).toSet()) // Up to four times over
        assertEquals((4..9).toSet(), counts("minecraft:lapis_ore", pickaxe).toSet())
        assertEquals((4..8).toSet(), counts("minecraft:redstone_ore", fortune).toSet()) // One more for each level
        assertEquals(listOf("minecraft:raw_iron"), dropOf("minecraft:deepslate_iron_ore", pickaxe))
    }

    @Test
    fun `ores give experience, unless Silk Touch takes them whole, and iron gives none`() {
        val experience = (1..300).map { drops.experienceOf(block("minecraft:diamond_ore"), pickaxe) }
        assertEquals((3..7).toSet(), experience.toSet())
        assertEquals(0, drops.experienceOf(block("minecraft:diamond_ore"), silkTouch))
        assertEquals(0, drops.experienceOf(block("minecraft:iron_ore"), pickaxe))
    }

    @Test
    fun `leaves drop saplings, sticks and apples by chance, and gravel flint`() {
        val fromLeaves = (1..4000).flatMap { dropOf("minecraft:oak_leaves") }.groupingBy { it }.eachCount()
        assertEquals(setOf("minecraft:oak_sapling", "minecraft:stick", "minecraft:apple"), fromLeaves.keys)
        assertTrue(fromLeaves.getValue("minecraft:oak_sapling") in 140..260) // One in twenty
        assertTrue(fromLeaves.getValue("minecraft:apple") in 5..40) // One in two hundred

        val shovel = tool("minecraft:iron_shovel")
        val fromGravel = (1..1000).flatMap { dropOf("minecraft:gravel", shovel) }.groupingBy { it }.eachCount()
        assertTrue(fromGravel.getValue("minecraft:flint") in 60..140) // One in ten
        assertEquals(listOf("minecraft:flint"), dropOf("minecraft:gravel", tool("minecraft:iron_shovel", FORTUNE to 3))) // Always, with Fortune III
    }

    @Test
    fun `glowstone and melons break into pieces, which Fortune adds to, up to a point`() {
        assertEquals((2..4).toSet(), counts("minecraft:glowstone", pickaxe).toSet())
        assertEquals((2..4).toSet(), counts("minecraft:glowstone", fortune).toSet())
        assertEquals((3..9).toSet(), counts("minecraft:melon_block", tool("minecraft:diamond_axe", FORTUNE to 3)).toSet())
        assertEquals(listOf("minecraft:glowstone"), dropOf("minecraft:glowstone", silkTouch))
        assertEquals(listOf("minecraft:book"), dropOf("minecraft:bookshelf"))
    }

    @Test
    fun `what a frame or flower pot holds drops with it`() {
        val diamond = ItemStack(data.items.first { it.name == "minecraft:diamond" })
        val frame = NbtMap.builder().putString("id", "ItemFrame").putCompound("Item", ItemStacks.nbtOf(diamond, 0)).build()
        assertEquals(listOf("minecraft:frame", "minecraft:diamond"), drops.of(block("minecraft:frame"), frame).map { it.item.name })

        val pot = NbtMap.builder().putString("id", "FlowerPot").putCompound("PlantBlock", blockStateNbt(block("minecraft:poppy"))).build()
        assertEquals(listOf("minecraft:flower_pot", "minecraft:poppy"), drops.of(block("minecraft:flower_pot"), pot).map { it.item.name })
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
