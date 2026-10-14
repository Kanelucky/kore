package org.koremc.storage

import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.inventory.SlotStack
import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.ItemVersion
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import org.koremc.world.BlockPos
import org.koremc.world.ChunkPos
import org.koremc.world.Effect
import org.koremc.world.FURNACE_FUEL
import org.koremc.world.FURNACE_INPUT
import org.koremc.world.Face
import org.koremc.world.Vec3
import org.koremc.world.beaconPowers
import org.koremc.world.setBeaconPowers
import org.koremc.world.tickBlocks
import org.koremc.world.use
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStorageTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val bedrock = state("minecraft:bedrock")
    private val dirt = state("minecraft:dirt")
    private val grass = state("minecraft:grass_block")
    private val stone = state("minecraft:stone")
    private val chest = state("minecraft:chest", "minecraft:cardinal_direction" to "south")
    private val furnace = state("minecraft:furnace", "minecraft:cardinal_direction" to "south")
    private val litFurnace = state("minecraft:lit_furnace", "minecraft:cardinal_direction" to "south")
    private val beacon = state("minecraft:beacon")
    private val buttons = (0..1).map { pressed -> state("minecraft:stone_button", "button_pressed_bit" to pressed.toByte(), "facing_direction" to 1) }
    private val fence = state("minecraft:oak_fence")
    private val stillWater = state("minecraft:water", "liquid_depth" to 0)
    private val blocks = BlockPalette(listOf(state(AIR), bedrock, dirt, grass, stone, chest, furnace, litFurnace, beacon, fence, stillWater) + buttons)
    private val layers = FlatLayers(listOf(bedrock, dirt, dirt, grass))

    private val coal = ItemDefinition("minecraft:coal", 1, componentBased = false, ItemVersion.NONE)
    private val rawIron = ItemDefinition("minecraft:raw_iron", 2, componentBased = false, ItemVersion.NONE)
    private val items = ItemStacks(listOf(coal, rawIron), CreativeInventory(emptyList(), emptyList()), blocks)
    private val settings = WorldSettings(GameMode.SURVIVAL, Difficulty.NORMAL, cheats = true, seed = 7, version = "1.26.50", protocol = 2193)

    private val directory = createTempDirectory("kore-world")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun open() = WorldStorage.open(directory, "Test", layers, blocks, items, settings)

    /** Opens the world, does [then] to it, saves it and closes it. */
    private fun session(then: WorldStorage.() -> Unit) = open().apply(then).apply { save(); close() }

    // The ground's top, and a block above it, in chunk 0, 0 and in another a way off.
    private val here = BlockPos(5, -60, 5)
    private val there = BlockPos(100, -60, 100)

    @Test
    fun `a new world saves its settings and name as it's made, and opens again with the blocks players changed, and the same ground elsewhere`() {
        session { world[here] = stone }
        assertTrue(directory.resolve("level.dat").exists())
        assertEquals("Test", directory.resolve("levelname.txt").readText())

        session {
            assertEquals(stone, world.stateAt(here))
            assertEquals(grass, world.stateAt(here.copy(y = -61)))
            assertEquals(grass, world.stateAt(there.copy(y = -61)))
            assertEquals(AIR, world.stateAt(there)?.name)
            world[there] = stone
            world[here] = blocks.stateOf(AIR)
        }
        session {
            assertEquals(AIR, world.stateAt(here)?.name)
            assertEquals(stone, world.stateAt(there))
        }
    }

    @Test
    fun `a world made before keeps its game mode, unless the game mode's forced, and its seed`() {
        session {}
        val creative = settings.copy(gameMode = GameMode.CREATIVE, seed = 99)

        WorldStorage.open(directory, "Test", layers, blocks, items, creative).use { assertEquals(listOf(GameMode.SURVIVAL, 7L), listOf(it.settings.gameMode, it.settings.seed)) }
        WorldStorage.open(directory, "Test", layers, blocks, items, creative.copy(forceGameMode = true)).use { forced ->
            assertEquals(listOf(GameMode.CREATIVE, 7L), listOf(forced.settings.gameMode, forced.settings.seed))
            forced.save()
        }
        WorldStorage.open(directory, "Test", layers, blocks, items, settings).use { assertEquals(GameMode.CREATIVE, it.settings.gameMode, "as it was forced to last time") }
    }

    @Test
    fun `the time, the weather and the game rules save, and open again as they were`() {
        session {
            world.time = 30_000
            world.gameTime = 45_000
            world.weather.raining = true
            world.weather.rainTime = 1234
            world.rules.set("doDaylightCycle", false)
            world.rules.set("randomTickSpeed", 3)
        }
        session {
            assertEquals(30_000, world.time)
            assertEquals(45_000, world.gameTime)
            assertTrue(world.weather.raining)
            assertFalse(world.weather.thundering)
            assertEquals(1234, world.weather.rainTime)
            assertFalse(world.rules.doDaylightCycle)
            assertEquals(3, world.rules["randomtickspeed"])
            assertEquals(true, world.rules["showTags"]) // Untouched, as it was
        }
    }

    @Test
    fun `chests keep their items, furnaces their fuel, what they cook and how far along they are, and beacons their powers`() {
        val furnaceAt = here.copy(x = 7)
        val beaconAt = here.copy(x = 9)
        session {
            world[here] = chest
            world.containerAt(here)[3] = SlotStack(ItemStack(coal, 5), world.newStackId())
            world[furnaceAt] = litFurnace
            world.furnaceAt(furnaceAt)!!.apply {
                slots[FURNACE_INPUT] = SlotStack(ItemStack(rawIron, 2), world.newStackId())
                slots[FURNACE_FUEL] = SlotStack(ItemStack(coal), world.newStackId())
                restore(litTime = 900, litDuration = 1600, cookTime = 50, experience = 1.4f)
            }
            world[beaconAt] = beacon
            world.setBeaconPowers(beaconAt, Effect.SPEED, 0)
        }
        session {
            assertEquals(ItemStack(coal, 5), world.containerAt(here)[3]?.stack)
            val cooking = world.furnaceAt(furnaceAt)!!
            assertEquals(ItemStack(rawIron, 2), cooking.slots[FURNACE_INPUT]?.stack)
            assertEquals(listOf(900, 1600, 50), listOf(cooking.litTime, cooking.litDuration, cooking.cookTime))
            assertEquals(1f, cooking.experience)
            assertEquals(Effect.SPEED to 0, world.beaconPowers(beaconAt))
            assertTrue(beaconAt in world.beacons)
            assertNull(world.blockEntityAt(here)?.getList("Items", NbtType.COMPOUND, null), "the items are kept apart, not sent to clients")
        }
    }

    @Test
    fun `water inside a block saves, as the game's second layer, and comes back inside it`() {
        session {
            world[here] = fence
            world.setLiquid(here, stillWater)
        }
        session {
            assertEquals(fence, world.stateAt(here))
            assertEquals(stillWater, world.liquidAt(here))
            assertNull(world.liquidAt(there))
        }
    }

    @Test
    fun `a chest broken after the world opens again goes from the saved chunk too`() {
        session {
            world[here] = chest
            world.containerAt(here)[0] = SlotStack(ItemStack(coal), world.newStackId())
        }
        session { world[here] = blocks.stateOf(AIR) }
        WorldDatabase.open(directory.resolve("db")).use { db -> assertNull(db[chunkKey(ChunkPos(0, 0), BLOCK_ENTITIES)]) }
    }

    @Test
    fun `a button pressed as the world's saved pops back out when it would have, once the world's open again, and the game's own ticks are kept`() {
        session {
            world[here] = buttons[0]
            world.use(here, Face.NORTH)
            repeat(5) { world.tickBlocks() }
        }
        // The game left a tick for the ground in the same chunk, which Kore doesn't tick.
        val ground = NbtMap.builder()
            .putInt("x", 1).putInt("y", -61).putInt("z", 1).putLong("time", 50)
            .putCompound("blockState", NbtMap.builder().putString("name", grass.name).putCompound("states", NbtMap.EMPTY).build())
            .build()
        WorldDatabase.open(directory.resolve("db")).use { db ->
            val saved = ticksIn(db)
            assertEquals(1, saved.getList("tickList", NbtType.COMPOUND).size)
            db.write { set(chunkKey(ChunkPos(0, 0), PENDING_TICKS), nbt(saved.toBuilder().putList("tickList", NbtType.COMPOUND, saved.getList("tickList", NbtType.COMPOUND) + ground).build())) }
        }

        session {
            assertEquals(buttons[1], world.stateAt(here))
            repeat(14) { world.tickBlocks() }
            assertEquals(buttons[1], world.stateAt(here), "a stone button stays in for 20 ticks")
            world.tickBlocks()
            assertEquals(buttons[0], world.stateAt(here))
        }
        WorldDatabase.open(directory.resolve("db")).use { db ->
            val saved = ticksIn(db)
            val kept = saved.getList("tickList", NbtType.COMPOUND).single()
            assertEquals(grass.name, kept.getCompound("blockState").getString("name"))
            assertEquals(45L, kept.getLong("time") - saved.getInt("currentTick"), "as long to go as it had")
        }
    }

    @Test
    fun `items and orbs lying in the world come back with their chunk, and entities Kore doesn't have stay as they were`() {
        session {
            world.spawnItem(ItemStack(coal, 3), Vec3(5.5f, -60f, 5.5f), Vec3(0f, 0f, 0f), pickupDelay = 10)
            world.spawnOrb(7, Vec3(6f, -60f, 6f), Vec3(0f, 0f, 0f))
        }
        // The game left something Kore doesn't have, say a pig, in the same chunk.
        val pig = NbtMap.builder().putString("identifier", "minecraft:pig").putLong("UniqueID", -99).putList("Pos", NbtType.FLOAT, listOf(5f, -60f, 5f)).build()
        WorldDatabase.open(directory.resolve("db")).use { db ->
            val listed = db[entityListKey(ChunkPos(0, 0))]!!
            db.write {
                set(entityKey(-99), nbt(pig))
                set(entityListKey(ChunkPos(0, 0)), listed + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-99).array())
            }
        }

        session {
            assertEquals(emptyList(), world.items.toList(), "not until the chunk's looked at")
            world.chunkAt(ChunkPos(0, 0))
            assertEquals(ItemStack(coal, 3), world.items.single().stack)
            assertEquals(10, world.items.single().pickupDelay)
            assertEquals(7, world.orbs.single().value)
            world.removeItem(world.items.single().id)
        }
        WorldDatabase.open(directory.resolve("db")).use { db ->
            val ids = ByteBuffer.wrap(db[entityListKey(ChunkPos(0, 0))]!!).order(ByteOrder.LITTLE_ENDIAN).let { list -> List(list.remaining() / 8) { list.getLong() } }
            assertEquals(2, ids.size, "the orb and the pig, the item gone")
            assertTrue(-99L in ids)
            assertNotNull(db[entityKey(-99)])
        }
    }

    @Test
    fun `maps keep what's drawn on them, their parents and names, and players their own data`() {
        val player = UUID.randomUUID()
        val data = NbtMap.builder().putInt("PlayerLevel", 12).build()
        var mapId = 0L
        session {
            val map = world.maps.start(0, 0, showsPlayers = true)
            map[3, 4] = 0xFF112233.toInt()
            mapId = map.id
            save(player, data)
        }
        session {
            val map = world.maps[mapId]!!
            assertEquals(0xFF112233.toInt(), map[3, 4])
            assertEquals(0, map[4, 4])
            assertTrue(map.showsPlayers)
            assertEquals(1, map.nameIndex)
            assertEquals(5, world.maps.lineage(map).size)
            assertEquals(2, world.maps.newNameIndex(), "names count on from the maps there are")
            assertEquals(data, load(player))
            assertNull(load(UUID.randomUUID()))
        }
    }

    @Test
    fun `every one of the game's block states saves and reads back as itself`() {
        assumeGameData()
        val vanilla = VanillaData.load(gameData)
        val states = vanilla.blocks.states.filter { it.name != "minecraft:unknown" }
        val vanillaItems = ItemStacks(vanilla.items, vanilla.creative, vanilla.blocks)
        fun real() = WorldStorage.open(directory, "Real", FlatLayers(org.koremc.world.FlatWorld.vanillaLayers(vanilla.blocks)), vanilla.blocks, vanillaItems, settings)
        // Each state in a block of its own, spread over a few chunks, well above the ground.
        fun spot(i: Int) = BlockPos((i % 64) * 2, -40 + (i / 64) % 200, (i / 12800) * 2)
        real().apply {
            states.forEachIndexed { i, state -> world[spot(i)] = state }
            save()
            close()
        }
        real().apply {
            val wrong = states.withIndex().filter { (i, state) -> world.stateAt(spot(i)) != state }.map { it.value }
            assertEquals(emptyList(), wrong.take(10), "${wrong.size} states came back otherwise")
            close()
        }
    }

    /** The ticks saved for chunk 0, 0. */
    private fun ticksIn(db: WorldDatabase) = NbtUtils.createReaderLE(ByteArrayInputStream(db[chunkKey(ChunkPos(0, 0), PENDING_TICKS)]!!)).readTag() as NbtMap

    private fun nbt(tag: NbtMap): ByteArray {
        val out = Unpooled.buffer()
        NbtUtils.createWriterLE(ByteBufOutputStream(out)).writeTag(tag)
        return ByteBufUtil.getBytes(out)
    }
}
