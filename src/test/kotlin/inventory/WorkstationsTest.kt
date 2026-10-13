package org.koremc.inventory

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.protocol.ContainerName
import org.koremc.protocol.SlotChange
import org.koremc.protocol.SlotRef
import org.koremc.protocol.StackAction
import org.koremc.protocol.StackRequest
import org.koremc.registry.Cost
import org.koremc.registry.CreativeInventory
import org.koremc.registry.Enchantment
import org.koremc.registry.EnchantmentLevel
import org.koremc.registry.Enchantments
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.Recipes
import org.koremc.registry.ShapelessRecipe
import org.koremc.registry.SmithingTransformRecipe
import org.koremc.registry.SmithingTrimRecipe
import org.koremc.registry.customName
import org.koremc.registry.damage
import org.koremc.registry.enchantments
import org.koremc.registry.repairCost
import org.koremc.registry.withCustomName
import org.koremc.registry.withDamage
import org.koremc.registry.withEnchantments
import org.koremc.registry.withRepairCost
import org.koremc.world.Effect
import org.koremc.world.MapData
import org.koremc.world.Maps
import org.koremc.world.mapId
import org.koremc.world.showing
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkstationsTest {
    private fun item(name: String, id: Int, stack: Int = 64, durability: Int = 0, repair: Set<String> = emptySet(), categories: Set<String> = emptySet(), enchantability: Int = 0) =
        ItemDefinition("minecraft:$name", id, componentBased = false, ItemVersion.NONE, maxStackSize = stack, maxDurability = durability, repairItems = repair, enchantCategories = categories, enchantability = enchantability)

    private val stone = item("stone", 1)
    private val slab = item("stone_slab", 2)
    private val diamond = item("diamond", 3)
    private val sword = item("diamond_sword", 4, stack = 1, durability = 1561, repair = setOf("minecraft:diamond"), categories = setOf("sharp_weapon", "durability", "vanishing"), enchantability = 10)
    private val netheriteSword = item("netherite_sword", 5, stack = 1, durability = 2031)
    private val netherite = item("netherite_ingot", 6)
    private val upgrade = item("netherite_upgrade_smithing_template", 7)
    private val chestplate = item("iron_chestplate", 8, stack = 1, durability = 240, categories = setOf("armor"))
    private val gold = item("gold_ingot", 9)
    private val coast = item("coast_armor_trim_smithing_template", 10)
    private val book = item("book", 11, enchantability = 1)
    private val enchantedBook = item("enchanted_book", 12, stack = 1)
    private val banner = item("banner", 13, stack = 16)
    private val redDye = item("red_dye", 14)
    private val creeperPattern = item("creeper_banner_pattern", 15, stack = 1)
    private val lapis = item("lapis_lazuli", 16)
    private val ironIngot = item("iron_ingot", 17)
    private val paper = item("paper", 18)
    private val emptyMap = item("empty_map", 19)
    private val filledMap = item("filled_map", 20)

    private val sharpness = Enchantment(9, "sharpness", 5, Cost(11, -10), Cost(11, 10), 10, treasure = false, curse = false, setOf("smite"), { "sharp_weapon" in it.enchantCategories })
    private val unbreaking = Enchantment(17, "unbreaking", 3, Cost(8, -3), Cost(10, 51), 5, treasure = false, curse = false, emptySet(), { "durability" in it.enchantCategories })
    private val smite = Enchantment(10, "smite", 5, Cost(8, -3), Cost(8, 17), 5, treasure = false, curse = false, setOf("sharpness"), { "sharp_weapon" in it.enchantCategories })
    private val vanishing = Enchantment(28, "vanishing_curse", 1, Cost(0, 25), Cost(0, 50), 1, treasure = true, curse = true, emptySet(), { "vanishing" in it.enchantCategories })
    private val enchantments = Enchantments(listOf(sharpness, unbreaking, smite, vanishing), book, enchantedBook)

    private val recipes = Recipes(
        listOf(
            ShapelessRecipe("slabs", listOf(Ingredient.Item(stone.name, null, 1)), listOf(ItemStack(slab, 2)), "stonecutter", 0, UUID(0, 1), keepsData = false, 1),
            SmithingTransformRecipe("upgrade", Ingredient.Item(upgrade.name, null, 1), Ingredient.Item(sword.name, null, 1), Ingredient.Item(netherite.name, null, 1), ItemStack(netheriteSword), "smithing_table", 2),
            SmithingTrimRecipe("trim", Ingredient.Tag("minecraft:trim_templates", 1), Ingredient.Tag("minecraft:trimmable_armors", 1), Ingredient.Tag("minecraft:trim_materials", 1), "smithing_table", 3),
            ShapelessRecipe("map", listOf(Ingredient.Item(paper.name, null, 1)), listOf(ItemStack(emptyMap)), "cartography_table", 0, UUID(0, 4), keepsData = false, 4),
        ),
        potionMixes = emptyList(),
        containerMixes = emptyList(),
        tags = mapOf("minecraft:trim_templates" to setOf(coast.name), "minecraft:trimmable_armors" to setOf(chestplate.name), "minecraft:trim_materials" to setOf(gold.name)),
    )

    private val inventory = PlayerInventory()
    private var creativeMode = false
    private var level = 30
    private val crafted = mutableListOf<Crafted>()

    /** How many layers of pyramid the open beacon stands on. */
    private var beaconLevel = 1

    /** The maps players have drawn. */
    private val maps = Maps()

    private val crafter = object : Crafter {
        override val experienceLevel get() = level

        override fun enchantingOffers(stack: ItemStack) = enchantments.offersFor(stack, bookshelves = 15, seed = 42)

        override fun beaconGives(primary: Int, secondary: Int) = org.koremc.world.beaconGives(beaconLevel, primary, secondary)

        override fun cartograph(input: ItemStack?, additional: ItemStack?, name: String?) =
            org.koremc.world.cartograph(input, additional, name, maps)
    }
    private val handler = StackRequestHandler(
        inventory, CreativeInventory(emptyList(), emptyList()), creativeMode = { creativeMode }, onDrop = {}, recipes = recipes,
        enchantments = enchantments, crafter = crafter, onCrafted = { crafted += it },
    )

    /** Opens [workstation], with [stacks] in its slots, by the names the client gives them. */
    private fun open(workstation: Workstation, vararg stacks: Pair<Int, ItemStack>) {
        inventory.workstation = workstation
        for ((name, stack) in stacks) inventory.ui[workstation.slots.getValue(name)] = SlotStack(stack, inventory.newStackId())
    }

    private fun inSlot(name: Int) = inventory.ui[inventory.workstation!!.slots.getValue(name)]?.stack

    /** Crafts with [craft], then takes [count] of what it made to the hotbar's first slot. */
    private fun crafts(craft: StackAction, count: Int = 1, names: List<String> = emptyList(), requestId: Int = -1) = handler.handle(
        StackRequest(
            requestId,
            listOf(craft, StackAction.Transfer(count, SlotRef(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT, requestId), SlotRef(ContainerName.HOTBAR, 0, 0))),
            names,
        ),
    ).accepted

    private val made get() = inventory.items[0]?.stack

    @Test
    fun `a stonecutter cuts what's in it into what the recipe makes, as many times over as asked`() {
        open(Workstation.STONECUTTER, ContainerName.STONECUTTER_INPUT to ItemStack(stone, 5))

        assertTrue(crafts(StackAction.Craft(1, 3), count = 6))

        assertEquals(ItemStack(slab, 6), made)
        assertEquals(2, inSlot(ContainerName.STONECUTTER_INPUT)?.count)
    }

    @Test
    fun `a smithing table upgrades a sword, keeping its enchantments and wear`() {
        val enchanted = ItemStack(sword).withEnchantments(listOf(EnchantmentLevel(9, 3))).withDamage(100)
        open(
            Workstation.SMITHING_TABLE,
            ContainerName.SMITHING_TABLE_INPUT to enchanted,
            ContainerName.SMITHING_TABLE_MATERIAL to ItemStack(netherite, 2),
            ContainerName.SMITHING_TABLE_TEMPLATE to ItemStack(upgrade),
        )

        assertTrue(crafts(StackAction.Craft(2, 1)))

        assertEquals(ItemStack(netheriteSword, nbt = enchanted.nbt), made)
        assertNull(inSlot(ContainerName.SMITHING_TABLE_INPUT))
        assertEquals(1, inSlot(ContainerName.SMITHING_TABLE_MATERIAL)?.count)
        assertNull(inSlot(ContainerName.SMITHING_TABLE_TEMPLATE))
    }

    @Test
    fun `a smithing table trims armour in the template's pattern and the material's colour`() {
        open(
            Workstation.SMITHING_TABLE,
            ContainerName.SMITHING_TABLE_INPUT to ItemStack(chestplate),
            ContainerName.SMITHING_TABLE_MATERIAL to ItemStack(gold),
            ContainerName.SMITHING_TABLE_TEMPLATE to ItemStack(coast),
        )

        assertTrue(crafts(StackAction.Craft(3, 1)))

        assertEquals(NbtMap.builder().putString("Material", "gold").putString("Pattern", "coast").build(), made?.nbt?.getCompound("Trim"))
    }

    @Test
    fun `an anvil mends with its material, a quarter at a time, a level each, until it's whole`() {
        open(Workstation.ANVIL, ContainerName.ANVIL_INPUT to ItemStack(sword).withDamage(1000), ContainerName.ANVIL_MATERIAL to ItemStack(diamond, 5))

        assertTrue(crafts(StackAction.CraftOptional(0, 0)))

        // Two quarters, of 390 each, then the 220 left.
        assertEquals(0, made?.damage)
        assertEquals(1, made?.repairCost)
        assertEquals(2, inSlot(ContainerName.ANVIL_MATERIAL)?.count)
        assertEquals(listOf(Crafted(Workstation.ANVIL, levels = 3)), crafted)
    }

    @Test
    fun `an enchanted book's enchantments go onto an item, and two the same level make one a level higher`() {
        val book = ItemStack(enchantedBook).withEnchantments(listOf(EnchantmentLevel(9, 3)))
        open(Workstation.ANVIL, ContainerName.ANVIL_INPUT to ItemStack(sword).withEnchantments(listOf(EnchantmentLevel(9, 3))), ContainerName.ANVIL_MATERIAL to book)

        assertTrue(crafts(StackAction.CraftOptional(0, 0)))

        assertEquals(listOf(EnchantmentLevel(9, 4)), made?.enchantments)
        // A common enchantment from a book costs a level for each level it adds.
        assertEquals(1, crafted.single().levels)
    }

    @Test
    fun `an enchantment that clashes with one already on stays off, and costs a level`() {
        val anvilled = enchantments.anvil(
            ItemStack(sword).withEnchantments(listOf(EnchantmentLevel(9, 1))),
            ItemStack(enchantedBook).withEnchantments(listOf(EnchantmentLevel(10, 2), EnchantmentLevel(17, 1))),
            name = null,
        )

        assertNotNull(anvilled)
        assertEquals(listOf(EnchantmentLevel(9, 1), EnchantmentLevel(17, 1)), anvilled.output.enchantments)
        assertEquals(1 + 1, anvilled.cost) // The clash, and Unbreaking I at half of 2 from a book
    }

    @Test
    fun `naming an item costs a level, and renaming alone is never too expensive`() {
        level = 39
        open(Workstation.ANVIL, ContainerName.ANVIL_INPUT to ItemStack(sword).withRepairCost(63))

        assertTrue(crafts(StackAction.CraftOptional(0, 0), names = listOf("Blade")))

        assertEquals("Blade", made?.customName)
        assertEquals(63, made?.repairCost)
        assertEquals(39, crafted.single().levels)
        // Naming it what it's called already does nothing.
        assertNull(enchantments.anvil(ItemStack(sword).withCustomName("Blade"), null, "Blade"))
    }

    @Test
    fun `outside creative, an anvil can't cost more levels than the player has, nor forty`() {
        val worked = ItemStack(sword).withDamage(1000).withRepairCost(39)
        open(Workstation.ANVIL, ContainerName.ANVIL_INPUT to worked, ContainerName.ANVIL_MATERIAL to ItemStack(diamond))
        assertFalse(crafts(StackAction.CraftOptional(0, 0)))

        creativeMode = true
        assertTrue(crafts(StackAction.CraftOptional(0, 0), requestId = -3))
        assertEquals(0, crafted.single().levels)

        creativeMode = false
        level = 0
        open(Workstation.ANVIL, ContainerName.ANVIL_INPUT to ItemStack(sword).withDamage(10), ContainerName.ANVIL_MATERIAL to ItemStack(diamond))
        assertFalse(crafts(StackAction.CraftOptional(0, 0), requestId = -5))
    }

    @Test
    fun `a grindstone takes off every enchantment but curses, for experience, and a book with none left is a book`() {
        val cursed = ItemStack(sword).withEnchantments(listOf(EnchantmentLevel(9, 3), EnchantmentLevel(28, 1))).withRepairCost(3)
        val ground = enchantments.grind(cursed, null, Random(1))

        assertNotNull(ground)
        assertEquals(listOf(EnchantmentLevel(28, 1)), ground.output.enchantments)
        assertEquals(0, ground.output.repairCost)
        // Sharpness III is worth 23 at its cheapest, so half that to all of it.
        assertTrue(ground.experience in 12..23)

        val book = enchantments.grind(ItemStack(enchantedBook).withEnchantments(listOf(EnchantmentLevel(17, 2))), null)
        assertEquals(ItemStack(this.book), book?.output)

        // An item with nothing to take off is left alone.
        assertNull(enchantments.grind(ItemStack(sword), null))
    }

    @Test
    fun `a grindstone mends two of the same item together, a twentieth more`() {
        open(Workstation.GRINDSTONE, ContainerName.GRINDSTONE_INPUT to ItemStack(sword).withDamage(1000), ContainerName.GRINDSTONE_ADDITIONAL to ItemStack(sword).withDamage(1200))

        assertTrue(crafts(StackAction.CraftGrindstone(0, 1)))

        assertEquals(1561 - (561 + 361 + 78), made?.damage)
        assertNull(inSlot(ContainerName.GRINDSTONE_INPUT))
        assertNull(inSlot(ContainerName.GRINDSTONE_ADDITIONAL))
    }

    @Test
    fun `a loom weaves a pattern in the dye's colour, and a banner pattern's only with it there`() {
        open(Workstation.LOOM, ContainerName.LOOM_INPUT to ItemStack(banner, 2, aux = 15), ContainerName.LOOM_DYE to ItemStack(redDye, 4))

        assertTrue(crafts(StackAction.CraftLoom("bs", 2), count = 2))
        val pattern = made?.nbt?.getList("Patterns", NbtType.COMPOUND)?.single()
        assertEquals(NbtMap.builder().putString("Pattern", "bs").putInt("Color", 1).build(), pattern)
        assertEquals(2, made?.count)
        assertEquals(2, inSlot(ContainerName.LOOM_DYE)?.count)

        open(Workstation.LOOM, ContainerName.LOOM_INPUT to ItemStack(banner, aux = 15))
        assertFalse(crafts(StackAction.CraftLoom("cre", 1), requestId = -3))
        open(Workstation.LOOM, ContainerName.LOOM_MATERIAL to ItemStack(creeperPattern))
        inventory.items[0] = null
        assertTrue(crafts(StackAction.CraftLoom("cre", 1), requestId = -5))
        assertEquals(ItemStack(creeperPattern), inSlot(ContainerName.LOOM_MATERIAL))
    }

    @Test
    fun `an enchanting table's offers are the same for the same seed, the dearer needing more bookshelves`() {
        val offers = enchantments.offersFor(ItemStack(sword), bookshelves = 15, seed = 7)
        assertEquals(offers, enchantments.offersFor(ItemStack(sword), bookshelves = 15, seed = 7))
        assertEquals(3, offers.size)
        assertEquals(30, offers[2].cost)
        assertTrue(offers.all { offer -> offer.enchantments.isNotEmpty() && offer.enchantments.none { it.id == 28 } })
        assertTrue(enchantments.offersFor(ItemStack(sword), bookshelves = 0, seed = 7).all { it.cost <= 8 })
        // Nothing for what a table can't enchant, or what's enchanted already.
        assertTrue(enchantments.offersFor(ItemStack(diamond), 15, 7).isEmpty())
        assertTrue(enchantments.offersFor(ItemStack(sword).withEnchantments(listOf(EnchantmentLevel(17, 1))), 15, 7).isEmpty())
    }

    @Test
    fun `enchanting takes as many levels and lapis lazuli as the offer's place, and a book becomes an enchanted book`() {
        open(Workstation.ENCHANTING_TABLE, ContainerName.ENCHANTING_INPUT to ItemStack(book), ContainerName.ENCHANTING_MATERIAL to ItemStack(lapis, 5))
        val offer = crafter.enchantingOffers(ItemStack(book))[2]

        val enchanted = handler.handle(
            StackRequest(
                -1,
                listOf(
                    StackAction.Craft(recipes.nextNetworkId + 2, 1),
                    StackAction.Transfer(1, SlotRef(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT, -1), SlotRef(ContainerName.ENCHANTING_INPUT, ENCHANTING_INPUT_SLOT, 0)),
                ),
            ),
        )

        assertTrue(enchanted.accepted)
        assertEquals(ItemStack(enchantedBook).withEnchantments(offer.enchantments), inSlot(ContainerName.ENCHANTING_INPUT))
        assertEquals(2, inSlot(ContainerName.ENCHANTING_MATERIAL)?.count)
        assertEquals(listOf(Crafted(Workstation.ENCHANTING_TABLE, levels = 3)), crafted)
    }

    /** Pays the open beacon for the powers [primary] and [secondary], followed by [then]. */
    private fun pays(primary: Int, secondary: Int = 0, then: List<StackAction> = emptyList(), requestId: Int = -1) =
        handler.handle(StackRequest(requestId, listOf(StackAction.BeaconPayment(primary, secondary)) + then))

    @Test
    fun `a beacon takes one of what pays it for the powers its pyramid gives, outside creative too, as the client destroys it`() {
        open(Workstation.BEACON, ContainerName.BEACON_PAYMENT to ItemStack(ironIngot))
        val payment = SlotRef(ContainerName.BEACON_PAYMENT, BEACON_PAYMENT_SLOT, inventory.ui[BEACON_PAYMENT_SLOT]!!.id)

        assertTrue(pays(Effect.HASTE, then = listOf(StackAction.Destroy(1, payment))).accepted)

        assertNull(inSlot(ContainerName.BEACON_PAYMENT))
        assertEquals(listOf(Crafted(Workstation.BEACON, beaconPowers = Effect.HASTE to 0)), crafted)
    }

    @Test
    fun `a beacon the client doesn't take the payment from takes it itself`() {
        open(Workstation.BEACON, ContainerName.BEACON_PAYMENT to ItemStack(ironIngot))

        val response = pays(Effect.SPEED)

        assertTrue(response.accepted)
        assertNull(inSlot(ContainerName.BEACON_PAYMENT))
        assertEquals(listOf(SlotChange(BEACON_PAYMENT_SLOT, 0, 0)), response.containers.single { it.container == ContainerName.BEACON_PAYMENT }.slots)
    }

    @Test
    fun `a beacon gives no power for nothing, nor for what doesn't pay it, nor one beyond its pyramid, and holds one of what pays`() {
        open(Workstation.BEACON)
        assertFalse(pays(Effect.SPEED).accepted)
        inventory.ui[BEACON_PAYMENT_SLOT] = SlotStack(ItemStack(stone), inventory.newStackId())
        assertFalse(pays(Effect.SPEED, requestId = -3).accepted)
        inventory.ui[BEACON_PAYMENT_SLOT] = SlotStack(ItemStack(ironIngot), inventory.newStackId())
        assertFalse(pays(Effect.STRENGTH, requestId = -5).accepted)
        assertFalse(pays(Effect.SPEED, Effect.REGENERATION, requestId = -7).accepted)
        beaconLevel = 4
        assertTrue(pays(Effect.STRENGTH, Effect.REGENERATION, requestId = -9).accepted)

        val slot = Location(inventory.ui, BEACON_PAYMENT_SLOT)
        assertEquals(1, inventory.limit(slot, ItemStack(ironIngot, 64)))
        assertEquals(0, inventory.limit(slot, ItemStack(stone)))
    }

    /** A map item showing a map numbered [id], which the cartography table knows. */
    private fun mapItem(id: Long = 7, count: Int = 1): ItemStack {
        val map = maps[id] ?: MapData(id, scale = 0, centreX = 0, centreZ = 0).also(maps::add)
        return ItemStack(filledMap, count).showing(map)
    }

    @Test
    fun `a cartography table copies a map onto an empty map, naming it for free`() {
        open(Workstation.CARTOGRAPHY_TABLE, ContainerName.CARTOGRAPHY_INPUT to mapItem(), ContainerName.CARTOGRAPHY_ADDITIONAL to ItemStack(emptyMap, 3))

        assertTrue(crafts(StackAction.CraftOptional(0, 0), count = 2, names = listOf("Home")))

        assertEquals(mapItem(count = 2).withCustomName("Home"), made)
        assertNull(inSlot(ContainerName.CARTOGRAPHY_INPUT))
        assertEquals(2, inSlot(ContainerName.CARTOGRAPHY_ADDITIONAL)?.count)
        assertEquals(listOf(Crafted(Workstation.CARTOGRAPHY_TABLE, made = made)), crafted)
    }

    @Test
    fun `a cartography table zooms a map out to its parent, and the player hears what it made`() {
        val map = maps.start(0, 0)
        open(Workstation.CARTOGRAPHY_TABLE, ContainerName.CARTOGRAPHY_INPUT to ItemStack(filledMap).showing(map), ContainerName.CARTOGRAPHY_ADDITIONAL to ItemStack(paper))

        assertTrue(crafts(StackAction.CraftOptional(0, 0)))

        assertEquals(map.parentId, made?.mapId)
        assertEquals(listOf(Crafted(Workstation.CARTOGRAPHY_TABLE, made = made)), crafted)
        assertNull(inSlot(ContainerName.CARTOGRAPHY_ADDITIONAL))
    }

    @Test
    fun `paper makes an empty map at a cartography table, by the table's own recipe, and the table holds only maps and what works them`() {
        open(Workstation.CARTOGRAPHY_TABLE, ContainerName.CARTOGRAPHY_INPUT to ItemStack(paper, 3))

        assertTrue(crafts(StackAction.Craft(4, 2), count = 2))

        assertEquals(ItemStack(emptyMap, 2), made)
        assertEquals(1, inSlot(ContainerName.CARTOGRAPHY_INPUT)?.count)
        assertEquals(0, inventory.limit(Location(inventory.ui, CARTOGRAPHY_INPUT_SLOT), ItemStack(stone)))
        assertEquals(64, inventory.limit(Location(inventory.ui, CARTOGRAPHY_INPUT_SLOT), ItemStack(paper)))
        assertEquals(64, inventory.limit(Location(inventory.ui, CARTOGRAPHY_ADDITIONAL_SLOT), ItemStack(paper)))
    }

    @Test
    fun `an enchanting table holds one item, and nothing but lapis beside it`() {
        open(Workstation.ENCHANTING_TABLE)
        inventory.items[0] = SlotStack(ItemStack(diamond, 3), inventory.newStackId())
        val diamonds = inventory.items[0]!!.id
        fun place(count: Int, to: Int, requestId: Int) = handler.handle(
            StackRequest(requestId, listOf(StackAction.Transfer(count, SlotRef(ContainerName.HOTBAR, 0, diamonds), SlotRef(to, inventory.workstation!!.slots.getValue(to), 0)))),
        ).accepted

        assertFalse(place(2, ContainerName.ENCHANTING_INPUT, -1))
        assertFalse(place(1, ContainerName.ENCHANTING_MATERIAL, -3))
        assertTrue(place(1, ContainerName.ENCHANTING_INPUT, -5))
    }
}
