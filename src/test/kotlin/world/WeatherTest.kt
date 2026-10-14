package org.koremc.world

import org.koremc.registry.BlockState
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The time, the weather, and what they do, by the game's own blocks. */
class WeatherTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")))
    }

    private fun block(name: String, vararg properties: Pair<String, Any>): BlockState {
        val default = blocks.defaultOf(name) ?: error("no block $name")
        return if (properties.isEmpty()) default else blocks.stateOf(name, default.properties + properties)
    }

    private fun tick(times: Int = 1) = (1..times).map { world.tickSky(emptyList()) }

    @Test
    fun `time passes while day turns to night, and stands still once it doesn't, while the game's own time runs on`() {
        tick(10)
        assertEquals(10, world.time)
        world.rules.set("doDaylightCycle", false)
        tick(5)
        assertEquals(10, world.time)
        assertEquals(15, world.gameTime)
    }

    @Test
    fun `rain starts once its time runs out, and lasts half a day to a day, unless the weather stays as it is`() {
        world.weather.rainTime = 3
        val ticked = tick(3)
        assertEquals(listOf(false, false, true), ticked.map { it.weatherChanged })
        assertTrue(world.weather.raining)
        tick()
        assertTrue(world.weather.rainTime in 12000..24000)

        world.rules.set("doWeatherCycle", false)
        world.weather.rainTime = 1
        tick(5)
        assertTrue(world.weather.raining)
    }

    @Test
    fun `thunder comes with rain, and the weather can be set for a while`() {
        world.setWeather(raining = false, thundering = true, ticks = 300)
        assertTrue(world.weather.raining && world.weather.thundering)
        assertEquals(300, world.weather.rainTime)

        world.setWeather(raining = false, thundering = false)
        assertTrue(world.weather.rainTime in 6000..18000)
    }

    @Test
    fun `lightning strikes the nearest lightning rod that's highest in its column, which powers what it's on a moment, and cleans the copper`() {
        val copper = BlockPos(20, above.y, 0)
        world[copper] = block("minecraft:weathered_copper")
        val rod = copper.side(Face.UP)
        world[rod] = block("minecraft:oxidized_lightning_rod", FACING_DIRECTION to Face.UP.ordinal)
        // A rod further off, and one under a block, draw nothing.
        world[BlockPos(60, above.y, 0)] = block("minecraft:lightning_rod", FACING_DIRECTION to Face.UP.ordinal)
        world[BlockPos(10, above.y, 0)] = block("minecraft:lightning_rod", FACING_DIRECTION to Face.UP.ordinal)
        world[BlockPos(10, above.y + 1, 0)] = block("minecraft:stone")

        val changed = mutableListOf<BlockPos>()
        val bolt = world.strike(BlockPos(0, above.y, 0), changed, mutableListOf(), Random(3))
        assertEquals(Vec3(20.5f, rod.y + 1f, 0.5f), bolt.position)
        assertEquals(listOf(bolt), world.lightning.toList())
        assertEquals("minecraft:lightning_rod", world.stateAt(rod)?.name) // Scraped clean
        assertEquals("minecraft:copper_block", world.stateAt(copper)?.name)
        assertTrue(world.stateAt(rod)!!.rodPowered)
        assertTrue(world.isPowered(copper.side(Face.EAST))) // Through the copper, which the rod powers strongly

        repeat(ROD_POWER_TICKS) { world.tickBlocks() }
        assertFalse(world.stateAt(rod)!!.rodPowered)

        repeat(20) { tick() }
        assertTrue(world.lightning.isEmpty())
    }

    @Test
    fun `lightning with no rod near strikes where it falls`() {
        val bolt = world.strike(BlockPos(200, above.y, 200), mutableListOf(), mutableListOf())
        assertEquals(Vec3(200.5f, above.y.toFloat(), 200.5f), bolt.position)
    }

    @Test
    fun `rain adds a level to a cauldron empty or of water, but not one of potion or lava`() {
        world[above] = block("minecraft:cauldron")
        assertEquals(above.side(Face.UP), world.topOf(above.x, above.z)) // Where rain falls in
        assertTrue(world.rainFills(above, world.stateAt(above)!!))
        assertEquals(1, world.stateAt(above)?.properties?.get("fill_level"))

        world[above] = block("minecraft:cauldron", "cauldron_liquid" to "lava", "fill_level" to 6)
        assertFalse(world.rainFills(above, world.stateAt(above)!!))
    }

    @Test
    fun `daylight detectors give power by the sky's light, full at noon and none at midnight, less in rain, and the other way inverted`() {
        val detector = block("minecraft:daylight_detector")
        val inverted = block("minecraft:daylight_detector_inverted")
        world.time = 6000
        assertEquals(15, world.daylightPower(above, detector))
        world.weather.raining = true
        assertEquals(12, world.daylightPower(above, detector))
        world.weather.thundering = true
        assertEquals(10, world.daylightPower(above, detector))
        world.weather.raining = false
        world.weather.thundering = false

        world.time = 18000
        assertEquals(0, world.daylightPower(above, detector))
        assertEquals(11, world.daylightPower(above, inverted))

        // Under a block, the sky's light spreads in from beside it, a level less, and shut in, it's dark whatever the time.
        world.time = 6000
        world[above.side(Face.UP).side(Face.UP)] = block("minecraft:stone")
        assertEquals(14, world.daylightPower(above, detector))
        for (side in Face.entries.filter { it != Face.DOWN }) world[above.side(side)] = block("minecraft:stone")
        assertEquals(0, world.daylightPower(above, detector))
    }

    @Test
    fun `daylight detectors look at the sky once a second, and keep looking once the world opens again`() {
        world.time = 6000
        world[above] = block("minecraft:daylight_detector")
        tick(20)
        assertEquals(15, world.stateAt(above)?.properties?.get(REDSTONE_SIGNAL))
        assertEquals("DaylightDetector", blockEntityFor(world.stateAt(above)!!, above, Placer(Face.NORTH), null)?.getString("id"))
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
