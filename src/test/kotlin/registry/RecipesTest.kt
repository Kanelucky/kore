package org.koremc.registry

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.gridUses
import org.koremc.protocol.CraftingData
import org.koremc.protocol.encode
import org.koremc.protocol.writeItem
import org.koremc.protocol.writeString
import org.koremc.protocol.writeUuid
import org.koremc.protocol.writeVarInt
import org.koremc.protocol.writeVarUInt
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipesTest {
    private val planks = ItemDefinition("minecraft:oak_planks", 5, componentBased = false, ItemVersion.NONE)
    private val stick = ItemDefinition("minecraft:stick", 323, componentBased = false, ItemVersion.NONE)

    @Test
    fun `recipes are read with their ingredients, by item or tag, and numbered in order`() {
        val items = ItemStacks(listOf(planks, stick), CreativeInventory(emptyList(), emptyList()), BlockPalette(emptyList()))
        val recipes = readRecipes(
            """
            {"recipes": [
                {"id": "minecraft:stick", "type": 1, "input": {"A": {"type": "item_tag", "count": 1, "itemTag": "minecraft:planks"}},
                 "output": [{"id": "minecraft:stick", "count": 4}], "shape": ["A", "A"], "block": "crafting_table", "priority": 0, "assumeSymetry": true},
                {"id": "minecraft:planks_from_sticks", "type": 0, "input": [{"type": "default", "count": 1, "id": "minecraft:stick", "auxValue": 32767}],
                 "output": [{"id": "minecraft:oak_planks"}], "block": "crafting_table", "priority": 0},
                {"type": 4, "uuid": "442d85ed-8272-4543-a6f1-418f90ded05d"}
            ]}
            """,
            items, BlockPalette(emptyList()), mapOf("minecraft:planks" to setOf(planks.name)),
        )

        val sticks = recipes[1] as ShapedRecipe
        assertEquals(1 to 2, sticks.width to sticks.height)
        assertEquals(listOf(Ingredient.Tag("minecraft:planks", 1), Ingredient.Tag("minecraft:planks", 1)), sticks.inputs)
        assertEquals(listOf(ItemStack(stick, 4)), sticks.outputs)
        assertEquals(listOf(Ingredient.Item("minecraft:stick", aux = null, count = 1)), (recipes[2] as ShapelessRecipe).inputs)
        assertEquals(MultiRecipe(UUID.fromString("442d85ed-8272-4543-a6f1-418f90ded05d"), 3), recipes[3])
        assertTrue(recipes.accepts(Ingredient.Tag("minecraft:planks", 1), ItemStack(planks)))
    }

    @Test
    fun `tags take in what the list says, what items name themselves, and newer items of a family`() {
        val shelf = ItemDefinition(
            "minecraft:shelf_mushroom", -1000, componentBased = true, ItemVersion.DATA_DRIVEN,
            NbtMap.builder().putCompound("components", NbtMap.builder().putList("item_tags", org.cloudburstmc.nbt.NbtType.STRING, listOf("minecraft:mushrooms_for_stew")).build()).build(),
        )
        val poplar = ItemDefinition("minecraft:poplar_planks", -1001, componentBased = false, ItemVersion.NONE)
        val tags = readItemTags("""{"minecraft:planks": ["minecraft:spruce_planks"]}""", listOf(shelf, poplar))

        assertEquals(setOf("minecraft:spruce_planks", "minecraft:poplar_planks"), tags["minecraft:planks"])
        assertEquals(setOf("minecraft:shelf_mushroom", "minecraft:red_mushroom", "minecraft:brown_mushroom"), tags["minecraft:mushrooms_for_stew"])
    }

    @Test
    fun `recipes go to the client in a list for each kind, ingredients named by item or tag`() {
        val recipe = ShapedRecipe(
            "minecraft:stick", 1, 2, listOf(Ingredient.Tag("minecraft:planks", 1), null), listOf(ItemStack(stick, 4)), "crafting_table",
            priority = 0, assumeSymmetry = true, UUID(1, 2), networkId = 1,
        )

        val expected = Unpooled.buffer().writeVarUInt(52)
            .writeVarUInt(1) // Shaped recipes
            .writeString("minecraft:stick").writeVarInt(1).writeVarInt(2)
            .writeVarUInt(2)
            .writeVarUInt(1).writeString("item_tag").writeString("minecraft:planks").writeVarInt(32767).writeVarInt(1)
            .writeVarUInt(0).writeVarInt(32767).writeVarInt(0) // Nothing in the second place
            .writeVarUInt(1).writeItem(ItemStack(stick, 4))
            .writeUuid(UUID(1, 2)).writeString("crafting_table").writeVarInt(0).writeBoolean(true).writeBoolean(false).writeVarUInt(1)
            .writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0) // Shapeless, multi, user data, both chemistry
            .writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0) // Smithing both ways, both mixes, reducers
            .writeBoolean(true)
        assertContentEquals(ByteBufUtil.getBytes(expected), CraftingData(Recipes(listOf(recipe), emptyList(), emptyList(), emptyMap())).encode())
    }

    @Test
    fun `the game's recipes load, and a log in the grid makes planks`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val recipes = vanilla.recipes

        assertTrue(recipes.recipes.size > 4000)
        assertTrue("minecraft:poplar_planks" in recipes.tagged("minecraft:planks"))
        val oakPlanks = recipes.recipes.filterIsInstance<ShapedRecipe>().single { it.id == "minecraft:oak_planks" }
        val log = ItemStack(vanilla.items.single { it.name == "minecraft:oak_log" })
        assertNotNull(recipes.gridUses(oakPlanks, listOf(null, null, log, null), times = 1))
        assertTrue(CraftingData(recipes).encode().size > 100_000)
        // Each recipe the client gets has a name of its own, which smithing recipes take from what they make.
        val smithing = recipes.recipes.filterIsInstance<SmithingTransformRecipe>().map { it.id }
        assertTrue("minecraft:smithing_netherite_sword" in smithing)
        assertEquals(smithing.size, smithing.distinct().size)
    }
}
