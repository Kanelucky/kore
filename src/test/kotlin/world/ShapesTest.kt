package org.koremc.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import org.koremc.registry.vanillaData
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shapes Kore gives the game's own blocks, against what the game says of them. */
class ShapesTest {
    @BeforeTest
    fun needGameData() = assumeGameData()

    @Test
    fun `no block the game lets everything through is a whole cube, but powder snow, which holds torches, and a block a piston moves`() {
        val passable = Json.parseToJsonElement(gameData.resolve("block_states.json").readText()).jsonArray.map { it.jsonObject }
            .filter { it["collisionShape"]!!.jsonArray.isEmpty() }
            .mapTo(HashSet()) { it["blockStateHash"]!!.jsonPrimitive.content.toLong().toInt() }
        val whole = data.blocks.states.filter { it.networkId in passable && it.isFullCube }.map { it.name }.distinct()
        assertEquals(listOf("minecraft:moving_block", "minecraft:powder_snow"), whole.sorted())
    }

    @Test
    fun `items fall through a nether portal`() {
        val stone = data.blocks.defaultOf("minecraft:stone")!!
        val world = FlatWorld(data.blocks, listOf(stone), traits = data.traits)
        val portal = BlockPos(0, MIN_Y + 3, 0)
        world[portal] = data.blocks.find(NETHER_PORTAL, mapOf("portal_axis" to "x"))!!
        val stack = ItemStacks(data.items, data.creative, data.blocks).of("minecraft:stick")!!
        val item = world.spawnItem(stack, Vec3(0.5f, portal.y + 1.5f, 0.5f), Vec3(0f, 0f, 0f), pickupDelay = 0)
        repeat(60) { world.tickItems() }
        assertTrue(item.position.y < portal.y, "the item came to rest at ${item.position.y}, not on the portal at ${portal.y}")
        assertEquals(MIN_Y + 1f, item.position.y, 0.01f) // On the stone
    }

    private companion object {
        val data: VanillaData get() = vanillaData
    }
}
