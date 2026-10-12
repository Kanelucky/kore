package org.koremc.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelTest {
    @Test
    fun `the game's fuel burns as long as it does, and wood from the Nether doesn't`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val items = vanilla.items.associateBy { it.name }
        val fuel = Fuel(vanilla.recipes, ItemStack(items.getValue("minecraft:bucket")))
        fun burns(name: String) = fuel.burnTicks(ItemStack(items.getValue("minecraft:$name")))

        assertEquals(1600, burns("coal"))
        assertEquals(20000, burns("lava_bucket"))
        assertEquals(300, burns("oak_log"))
        assertEquals(300, burns("dark_oak_planks"))
        assertEquals(150, burns("spruce_slab"))
        assertEquals(300, burns("fence_gate"))
        assertEquals(200, burns("cherry_door"))
        assertEquals(1200, burns("bamboo_raft"))
        assertEquals(200, burns("wooden_pickaxe"))
        assertEquals(200, burns("wooden_spear")) // Its components say ten seconds
        assertEquals(100, burns("red_wool"))
        assertEquals(67, burns("red_carpet"))
        assertNull(burns("crimson_planks"))
        assertNull(burns("warped_stem"))
        assertNull(burns("moss_carpet"))
        assertNull(burns("stone"))
        assertEquals(ItemStack(items.getValue("minecraft:bucket")), fuel.leftover(ItemStack(items.getValue("minecraft:lava_bucket"))))
    }

    @Test
    fun `smelting earns the game's experience`() {
        val ingot = ItemStack(ItemDefinition("minecraft:iron_ingot", 1, componentBased = false, ItemVersion.NONE))
        val terracotta = ItemStack(ItemDefinition("minecraft:red_glazed_terracotta", 2, componentBased = false, ItemVersion.NONE))
        val stick = ItemStack(ItemDefinition("minecraft:stick", 3, componentBased = false, ItemVersion.NONE))
        assertEquals(0.7f, smeltingExperience(ingot))
        assertEquals(0.1f, smeltingExperience(terracotta))
        assertEquals(0f, smeltingExperience(stick))
    }

    @Test
    fun `the game's enchantments load, the newest included, each taking the items it should`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val items = vanilla.items.associateBy { it.name }
        val enchantments = vanilla.enchantments

        assertEquals(42, enchantments.all.size)
        assertEquals((0..41).toList(), enchantments.all.map { it.id }.sorted())
        val sharpness = enchantments.named("sharpness")!!
        val thorns = enchantments.named("thorns")!!
        assertEquals(9, sharpness.id)
        assertEquals(true, sharpness.goesOn(items.getValue("minecraft:diamond_axe")))
        assertEquals(false, sharpness.goesOn(items.getValue("minecraft:mace")))
        assertEquals(true, enchantments.named("density")!!.goesOn(items.getValue("minecraft:mace")))
        assertEquals(true, enchantments.named("lunge")!!.goesOn(items.getValue("minecraft:iron_spear")))
        // Thorns goes on any armour, but a table offers it only for chestplates.
        assertEquals(true, thorns.goesOn(items.getValue("minecraft:iron_boots")))
        assertEquals(false, thorns.offeredFor(items.getValue("minecraft:iron_boots")))
        assertEquals(true, thorns.offeredFor(items.getValue("minecraft:iron_chestplate")))
        assertEquals(false, sharpness.compatibleWith(enchantments.named("smite")!!))
        assertEquals(false, enchantments.named("density")!!.compatibleWith(enchantments.named("smite")!!))
        assertEquals(false, enchantments.named("mending")!!.offeredFor(items.getValue("minecraft:diamond_sword")))
        assertEquals(items["minecraft:enchanted_book"], enchantments.enchantedBook)
    }
}
