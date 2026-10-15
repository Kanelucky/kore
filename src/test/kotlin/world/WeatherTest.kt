package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.vanillaData
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
    fun `snow adds a level of powder snow to a cauldron empty or of powder snow, but not one of water`() {
        world[above] = block("minecraft:cauldron")
        assertTrue(world.snowFills(above, world.stateAt(above)!!))
        assertEquals("powder_snow", world.stateAt(above)?.properties?.get("cauldron_liquid"))
        assertTrue(world.snowFills(above, world.stateAt(above)!!))
        assertEquals(2, world.stateAt(above)?.properties?.get("fill_level"))

        world[above] = block("minecraft:cauldron", "fill_level" to 2)
        assertFalse(world.snowFills(above, world.stateAt(above)!!))
    }

    private fun climateOf(name: String) = Climate.of(data.biomes.single { it.name == "minecraft:$name" })

    @Test
    fun `a biome's climate is the game's, rain or snow falling by how warm it is, colder higher up, and nothing in a dry one`() {
        assertEquals(Climate(0.8f), climateOf("plains"))
        assertEquals(Climate(0f, snowLayers = 8, snowCover = 2), climateOf("ice_plains"))
        assertEquals(Climate.PLAINS, Climate.of(null))

        fun fallsAt(biome: String, y: Int) = world.apply { climate = climateOf(biome) }.precipitationAt(BlockPos(0, y, 0))
        assertEquals(Precipitation.RAIN, fallsAt("plains", 300))
        assertEquals(Precipitation.NONE, fallsAt("desert", 0))
        assertEquals(Precipitation.SNOW, fallsAt("ice_plains", MIN_Y))
        assertEquals(Precipitation.RAIN, fallsAt("deep_ocean", 106))
        // Windswept hills snow above y=120.
        assertEquals(Precipitation.RAIN, fallsAt("extreme_hills", 115))
        assertEquals(Precipitation.SNOW, fallsAt("extreme_hills", 125))
    }

    @Test
    fun `in a thunderstorm, no lightning strikes where snow falls or nothing does, and a cauldron fills with what does`() {
        // Every chance taken, and the first block of each chunk picked.
        val always = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        world.rules.set("doWeatherCycle", false)
        world.setWeather(raining = true, thundering = true)
        world[above] = block("minecraft:cauldron")

        world.climate = climateOf("desert")
        assertTrue(world.tickSky(listOf(ChunkPos(0, 0)), always).strikes.isEmpty())
        assertEquals(0, world.stateAt(above)?.properties?.get("fill_level"))

        world.climate = climateOf("ice_plains")
        val snowing = world.tickSky(listOf(ChunkPos(0, 0)), always)
        assertTrue(snowing.strikes.isEmpty())
        assertTrue(above in snowing.changed)
        assertEquals("powder_snow", world.stateAt(above)?.properties?.get("cauldron_liquid"))

        world.climate = climateOf("plains")
        assertTrue(world.tickSky(listOf(ChunkPos(0, 0)), always).strikes.isNotEmpty())
    }

    @Test
    fun `snow settles where it's cold while it snows, a layer at a time, as deep as the biome piles it, but not in bright light or on ice`() {
        world = FlatWorld(blocks, listOf(block("minecraft:stone")), traits = data.traits) // Which say what gives off light
        val column = BlockPos(5, above.y, 5)
        fun snowAt() = world.stateAt(column)?.takeIf { it.name == SNOW_LAYER }?.number("height")?.plus(1) ?: 0
        fun snowFor(times: Int) = repeat(times) { world.weatherOn(world.topOf(column.x, column.z), Random(it)) }

        world.climate = climateOf("ice_plains")
        snowFor(5)
        assertEquals(0, snowAt(), "not without rain")
        world.setWeather(raining = true, thundering = false)
        snowFor(1)
        assertEquals(1, snowAt())
        snowFor(20)
        assertEquals(8, snowAt(), "a whole block of it, in snowy plains")

        world[column] = block(AIR)
        world.climate = Climate(0f, snowLayers = 2)
        snowFor(20)
        assertEquals(2, snowAt())

        world[column] = block(AIR)
        world.climate = climateOf("plains")
        snowFor(20)
        assertEquals(0, snowAt(), "it rains")

        world.climate = climateOf("ice_plains")
        world[column.side(Face.EAST)] = block("minecraft:glowstone")
        snowFor(20)
        assertEquals(0, snowAt(), "too bright")
        world[column.side(Face.EAST)] = block(AIR)
        world[column.side(Face.DOWN)] = block("minecraft:ice")
        snowFor(20)
        assertEquals(0, snowAt(), "on ice")
    }

    @Test
    fun `still water freezes where it's cold, in any weather, from its edges in`() {
        world.climate = climateOf("ice_plains")
        // Three by three of water on the ground, the middle with water all round it.
        val middle = BlockPos(5, above.y, 5)
        for (x in -1..1) for (z in -1..1) world[BlockPos(middle.x + x, middle.y, middle.z + z)] = world.liquidState(Liquid.WATER, 0)
        fun freeze(pos: BlockPos) = world.weatherOn(world.topOf(pos.x, pos.z), Random(1))

        assertEquals(emptyList(), freeze(middle))
        val edge = middle.side(Face.NORTH)
        assertEquals(listOf(edge), freeze(edge))
        assertEquals("minecraft:ice", world.stateAt(edge)?.name)
        assertEquals(listOf(middle), freeze(middle))

        world.climate = climateOf("plains")
        assertEquals(emptyList(), freeze(middle.side(Face.SOUTH)))
    }

    @Test
    fun `with growth off, no snow settles and no water freezes`() {
        world.features = Features(growth = false)
        world.climate = climateOf("ice_plains")
        world.setWeather(raining = true, thundering = false)
        world[above] = world.liquidState(Liquid.WATER, 0)
        assertEquals(emptyList(), world.weatherOn(world.topOf(above.x, above.z), Random(1)))
        assertEquals(emptyList(), world.weatherOn(world.topOf(9, 9), Random(1)))
        assertEquals(AIR, world.stateAt(BlockPos(9, above.y, 9))?.name)
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
        val data: VanillaData get() = vanillaData
    }
}
