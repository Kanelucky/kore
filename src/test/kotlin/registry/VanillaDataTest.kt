package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VanillaDataTest {
    @Test
    fun `items get their components by name, or none`() {
        val food = NbtMap.builder().putCompound("components", NbtMap.builder().putInt("nutrition", 4).build()).build()

        val items = readItems(
            """
            [
                {"name": "minecraft:apple", "id": 882, "version": 1, "componentBased": true},
                {"name": "minecraft:stick", "id": 323, "version": 2, "componentBased": false}
            ]
            """,
            NbtMap.builder().putCompound("minecraft:apple", food).build(),
        )

        assertEquals(
            listOf(
                ItemDefinition("minecraft:apple", 882, componentBased = true, ItemVersion.DATA_DRIVEN, food),
                ItemDefinition("minecraft:stick", 323, componentBased = false, ItemVersion.NONE),
            ),
            items,
        )
    }

    @Test
    fun `item lists that couldn't go on the wire are rejected`() {
        assertFailsWith<IllegalStateException> { readItems(items("a" to 1, "b" to 1), NbtMap.EMPTY) }
        assertFailsWith<IllegalStateException> { readItems(items("a" to 1, "a" to 2), NbtMap.EMPTY) }
        assertFailsWith<IllegalStateException> { readItems(items("a" to 40_000), NbtMap.EMPTY) }
        assertFailsWith<IllegalStateException> { readItems("""[{"name": "a", "id": 1, "version": 3, "componentBased": false}]""", NbtMap.EMPTY) }
    }

    @Test
    fun `biomes keep their tags and pack their map water colour as ARGB`() {
        val biomes = readBiomes(
            """
            {
                "minecraft:plains": {
                    "id": null, "temperature": 0.8, "downfall": 0.4, "redSporeDensity": 0.0, "foliageSnow": 0.0, "depth": 0.125, "scale": 0.05,
                    "mapWaterColor": {"a": 165, "r": 68, "g": 175, "b": 245}, "rain": true, "tags": ["animal", "plains"], "chunkGenData": null
                }
            }
            """,
        )

        assertEquals(
            listOf(BiomeDefinition("minecraft:plains", null, 0.8f, 0.4f, 0f, 0.125f, 0.05f, 0xA544AFF5.toInt(), rain = true, listOf("animal", "plains"))),
            biomes,
        )
    }

    @Test
    fun `biome IDs must be distinct and leave room for the missing ID marker`() {
        assertFailsWith<IllegalStateException> { readBiomes(biomes("a" to 65535)) }
        assertFailsWith<IllegalStateException> { readBiomes(biomes("a" to 3, "b" to 3)) }
        assertFailsWith<IllegalStateException> { readBiomes("{}") }
        assertEquals(listOf(3, null), readBiomes(biomes("a" to 3, "b" to null)).map { it.id })
    }

    @Test
    fun `creative items find their item by name and their block in the palette`() {
        val planksState = BlockState("minecraft:oak_planks", NbtMap.EMPTY, 18168865)
        val enchantment = NbtMap.builder().putShort("lvl", 1).build()
        val creative = """
            {
                "groups": [
                    {"name": "itemGroup.name.planks", "category": "construction", "icon": {"id": "minecraft:oak_planks", "block_state_b64": "${blockStateNbt("minecraft:oak_planks")}"}},
                    {"name": "", "category": "items", "icon": {"id": "minecraft:air"}}
                ],
                "items": [
                    {"id": "minecraft:oak_planks", "groupId": 0, "block_state_b64": "${blockStateNbt("minecraft:oak_planks")}"},
                    {"id": "minecraft:arrow", "groupId": 1, "damage": 6},
                    {"id": "minecraft:enchanted_book", "groupId": 1, "nbt_b64": "${base64(enchantment)}"}
                ]
            }
        """

        val inventory = readCreativeInventory(creative, listOf(planks, arrow, book), BlockPalette(listOf(planksState)))

        assertEquals(
            listOf(
                CreativeGroup(CreativeCategory.CONSTRUCTION, "itemGroup.name.planks", ItemStack(planks, block = planksState)),
                CreativeGroup(CreativeCategory.ITEMS, "", icon = null),
            ),
            inventory.groups,
        )
        assertEquals(
            listOf(
                CreativeItem(ItemStack(planks, block = planksState), group = 0),
                CreativeItem(ItemStack(arrow, aux = 6), group = 1),
                CreativeItem(ItemStack(book, nbt = enchantment), group = 1),
            ),
            inventory.items,
        )
    }

    @Test
    fun `creative items the client wouldn't know are rejected`() {
        val group = """{"name": "", "category": "items", "icon": {"id": "minecraft:air"}}"""
        val palette = BlockPalette(emptyList())

        assertFailsWith<IllegalStateException> { readCreativeInventory("""{"groups": [$group], "items": [{"id": "minecraft:nope"}]}""", listOf(arrow), palette) }
        assertFailsWith<IllegalStateException> { readCreativeInventory("""{"groups": [$group], "items": [{"id": "minecraft:arrow", "groupId": 1}]}""", listOf(arrow), palette) }
        assertFailsWith<IllegalArgumentException> {
            readCreativeInventory("""{"groups": [$group], "items": [{"id": "minecraft:arrow", "block_state_b64": "${blockStateNbt("minecraft:nope")}"}]}""", listOf(arrow), palette)
        }
    }

    @Test
    fun `the game's data loads`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val items = vanilla.items.associateBy { it.name }

        assertTrue(items.size > 1000)
        assertEquals(ItemDefinition("minecraft:shield", 358, componentBased = false, ItemVersion.NONE), items["minecraft:shield"])
        assertTrue(items.getValue("minecraft:apple").components.getCompound("components").containsKey("item_properties"))

        assertTrue(vanilla.blocks.states.size > 10_000)
        assertEquals("minecraft:air", vanilla.blocks[blockStateHash("minecraft:air", emptyMap())]?.name)

        val plains = vanilla.biomes.single { it.name == "minecraft:plains" }
        assertTrue(vanilla.biomes.size > 50)
        assertEquals(null, plains.id)
        assertTrue("plains" in plains.tags)

        // The blocks 26.50 defines in data, which clients can't show without their definitions.
        val defined = vanilla.blockDefinitions.map { it.name }
        assertTrue("minecraft:white_wool_stairs" in defined && "minecraft:red_concrete_slab" in defined && "minecraft:shelf_mushroom" in defined)
        assertTrue(vanilla.blockDefinitions.all { it.name in vanilla.blocks.states.map(BlockState::name) })

        // Recent additions, which a creative inventory from older data would be missing.
        val creative = vanilla.creative.items.map { it.stack.item.name }.toSet()
        assertTrue(vanilla.creative.items.size > 1000)
        for (name in listOf("minecraft:shelf_mushroom", "minecraft:poplar_shelf", "minecraft:white_wool_stairs", "minecraft:red_concrete_slab")) {
            assertTrue(name in creative, "$name is missing from the creative inventory")
        }
    }

    @Test
    fun `a missing data directory names the file it needed`() {
        val missing = assertFailsWith<NoSuchFileException> { VanillaData.load(Path.of("no-such-data")) }

        assertEquals(Path.of("no-such-data", "runtime_item_states.json").toString(), missing.file)
    }

    private val planks = ItemDefinition("minecraft:oak_planks", 5, componentBased = false, ItemVersion.NONE)
    private val arrow = ItemDefinition("minecraft:arrow", 303, componentBased = false, ItemVersion.DATA_DRIVEN)
    private val book = ItemDefinition("minecraft:enchanted_book", 530, componentBased = false, ItemVersion.NONE)

    /** A block state as creative_items.json has it: little-endian NBT in base64, with no properties here. */
    private fun blockStateNbt(name: String) = base64(NbtMap.builder().putString("name", name).putCompound("states", NbtMap.EMPTY).build())

    private fun base64(tag: NbtMap) =
        Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { NbtUtils.createWriterLE(it).writeTag(tag) }.toByteArray())

    private fun biomes(vararg entries: Pair<String, Int?>) = entries.joinToString(",", "{", "}") { (name, id) ->
        """"$name": {"id": $id, "temperature": 0, "downfall": 0, "foliageSnow": 0, "depth": 0, "scale": 0, "mapWaterColor": {"a": 0, "r": 0, "g": 0, "b": 0}, "rain": false}"""
    }

    private fun items(vararg entries: Pair<String, Int>) =
        entries.joinToString(",", "[", "]") { (name, id) -> """{"name": "$name", "id": $id, "version": 2, "componentBased": false}""" }
}
