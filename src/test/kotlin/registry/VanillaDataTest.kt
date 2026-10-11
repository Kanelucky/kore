package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
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
    fun `the game's data loads`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val items = vanilla.items.associateBy { it.name }

        assertTrue(items.size > 1000)
        assertEquals(ItemDefinition("minecraft:shield", 358, componentBased = false, ItemVersion.NONE), items["minecraft:shield"])
        assertTrue(items.getValue("minecraft:apple").components.getCompound("components").containsKey("item_properties"))

        assertTrue(vanilla.blocks.states.size > 10_000)
        assertEquals("minecraft:air", vanilla.blocks[blockStateHash("minecraft:air", emptyMap())]?.name)
    }

    @Test
    fun `a missing data directory names the file it needed`() {
        val missing = assertFailsWith<NoSuchFileException> { VanillaData.load(Path.of("no-such-data")) }

        assertEquals(Path.of("no-such-data", "runtime_item_states.json").toString(), missing.file)
    }

    private fun items(vararg entries: Pair<String, Int>) =
        entries.joinToString(",", "[", "]") { (name, id) -> """{"name": "$name", "id": $id, "version": 2, "componentBased": false}""" }
}
