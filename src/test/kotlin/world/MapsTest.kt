package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.protocol.MapMarker
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.BlockTrait
import org.koremc.registry.BlockTraits
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import org.koremc.registry.Tint
import org.koremc.registry.customName
import org.koremc.registry.withCustomName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapsTest {
    private fun state(name: String) = BlockState(name, NbtMap.EMPTY, version = 1)

    private val stone = state("minecraft:stone")
    private val dirt = state("minecraft:dirt")
    private val grass = state("minecraft:grass_block")
    private val water = state("minecraft:water")
    private val glass = state("minecraft:glass")
    private val palette = BlockPalette(listOf(state(AIR), stone, dirt, grass, water, glass))

    private val traits = BlockTraits(
        mapOf(
            stone.networkId to BlockTrait(0xFF707070.toInt(), Tint.NONE, 15),
            dirt.networkId to BlockTrait(0xFF976D4D.toInt(), Tint.NONE, 15),
            grass.networkId to BlockTrait(0, Tint.GRASS, 15),
            water.networkId to BlockTrait(0, Tint.WATER, 1),
            glass.networkId to BlockTrait(0, Tint.NONE, 0),
        ),
    )

    // Stone, dirt and grass at the bottom of the world: the grass is at y -62.
    private val world = FlatWorld(palette, listOf(stone, dirt, grass))
    private val top = -62

    private val plainsGrass = 0xFF91BD59.toInt()

    /** A map of the square around 0, 0, at [scale]. */
    private fun map(scale: Int = 0, id: Long = 1, showsPlayers: Boolean = false, locked: Boolean = false): MapData {
        val (x, z) = mapCentre(0, 0, scale)
        return MapData(id, scale, x, z, showsPlayers, locked)
    }

    /** Draws [map], held by a player standing at [x], [z]. */
    private fun draw(map: MapData, x: Float = 0.5f, z: Float = 0.5f) = world.drawMap(map, x, z, traits)

    /** The pixel showing the block column at [x], [z] on the map of the square around 0, 0. */
    private fun MapData.at(x: Int, z: Int) = this[x + MAP_SIZE / 2, z + MAP_SIZE / 2]

    @Test
    fun `maps line up in a grid at each scale, the smallest around 0, 0, each next one twice the size`() {
        assertEquals(0 to 0, mapCentre(0, 0, scale = 0))
        assertEquals(0 to 0, mapCentre(63, -64, scale = 0))
        assertEquals(128 to -128, mapCentre(64, -65, scale = 0))
        assertEquals(64 to 64, mapCentre(0, 0, scale = 1))
        // A level 3 map made at 0, 0 covers -64 to 959.
        assertEquals(448 to 448, mapCentre(0, 0, scale = 3))
    }

    @Test
    fun `a map starts with a parent at each scale above it, around the squares it's in, all named as it is`() {
        val map = maps.start(0, 0)
        val lineage = maps.lineage(map).map { maps[it]!! }
        assertEquals(map, lineage.first())
        assertEquals(listOf(0, 1, 2, 3, 4), lineage.map { it.scale })
        assertEquals(listOf(0, 64, 192, 448, 960), lineage.map { it.centreX })
        assertEquals(listOf(1), lineage.map { it.nameIndex }.distinct())
        assertNull(lineage.last().parentId)
        assertEquals(2, maps.start(0, 0).nameIndex)
    }

    @Test
    fun `a player holding a map draws the world's top around them all at once, grass in the biome's colour, and again as it changes`() {
        val map = map()
        draw(map)
        assertTrue(map.pixels.all { it == plainsGrass }, "all of it, from the middle")

        map.takeChanges()
        world[BlockPos(0, top, 0)] = stone
        draw(map)
        assertEquals(0xFF707070.toInt(), map.at(0, 0))
        assertEquals(listOf(listOf(64, 64, 1, 1)), map.takeChanges().map { listOf(it.x, it.y, it.width, it.height) }, "only it")
    }

    @Test
    fun `ground higher than the ground north of it shows brighter, and lower ground darker`() {
        world[BlockPos(0, top + 1, 0)] = stone
        val map = map()
        draw(map)
        assertEquals(0xFF838383.toInt(), map.at(0, 0))
        assertEquals(0xFF7BA04B.toInt(), map.at(0, 1))
        assertEquals(plainsGrass, map.at(1, 0))
    }

    @Test
    fun `glass shows what's under it, and water the ground, washed towards the water's colour the deeper it is`() {
        world[BlockPos(0, top + 3, 0)] = glass
        world[BlockPos(3, top + 1, 3)] = water
        for (y in top + 1..top + 16) world[BlockPos(6, y, 6)] = water
        val map = map()
        draw(map)
        assertEquals(plainsGrass, map.at(0, 0))
        assertEquals(0xFF6BB6F5.toInt(), map.at(3, 3))
        assertEquals(0xFF44AFF5.toInt(), map.at(6, 6))
    }

    @Test
    fun `a map is drawn as far as 128 blocks around the player, a pixel for every block or more at its scale, and a locked one never`() {
        val zoomed = map(scale = 1)
        draw(zoomed, x = 1000f, z = 1000f)
        assertTrue(zoomed.pixels.all { it == 0 }, "nothing within 128 blocks")
        draw(zoomed, x = 64.5f, z = 64.5f)
        val drawn = zoomed.pixels.count { it != 0 }
        // A circle of 64 pixels around its middle, two blocks each, less half its ragged edge.
        assertTrue(drawn in 12000..13000, "$drawn pixels")

        val locked = map(locked = true)
        draw(locked)
        assertTrue(locked.pixels.all { it == 0 })
    }

    @Test
    fun `what changed on a map goes out by the run of columns, once`() {
        val map = map()
        map[3, 10] = 1
        map[4, 2] = 1
        map[9, 5] = 1
        val changes = map.takeChanges()
        assertEquals(listOf(listOf(3, 2, 2, 9), listOf(9, 5, 1, 1)), changes.map { listOf(it.x, it.y, it.width, it.height) })
        assertEquals(1, changes[0].pixels[8 * 2 + 0])
        assertEquals(1, changes[0].pixels[0 * 2 + 1])
        assertEquals(emptyList(), map.takeChanges())
        // The same colour again is no change.
        map[3, 10] = 1
        assertEquals(emptyList(), map.takeChanges())
    }

    @Test
    fun `a player shows on a map as a pointer the way they look, as a dot on the edge off it, and not at all far off`() {
        val map = map(showsPlayers = true)
        assertEquals(MapMarker(MapMarker.PLAYER, 4, 21, -39), map.markerFor(10.25f, -20f, yaw = 90f))
        assertEquals(MapMarker(MapMarker.PLAYER, 8, 0, 0), map.markerFor(0f, 0f, yaw = -180f))
        assertEquals(MapMarker(MapMarker.PLAYER_OFF_MAP, 0, 127, -39), map.markerFor(100f, -20f, yaw = 0f))
        assertNull(map.markerFor(400f, 0f, yaw = 0f))
        // Twice the scale, twice as far to go off it.
        assertEquals(MapMarker.PLAYER, map(scale = 1).markerFor(100f, 100f, yaw = 0f)?.type)
    }

    private fun item(name: String, id: Int) = ItemDefinition(name, id, componentBased = false, ItemVersion.NONE)
    private val filledMap = item(FILLED_MAP, 1)
    private val emptyMap = item(EMPTY_MAP, 2)
    private val paper = item("minecraft:paper", 3)
    private val compass = item("minecraft:compass", 4)
    private val glassPane = item("minecraft:glass_pane", 5)

    private val maps = Maps()

    /** A map item showing [map], which [maps] know. */
    private fun mapItem(map: MapData, count: Int = 1): ItemStack {
        maps.add(map)
        return ItemStack(filledMap, count).showing(map)
    }

    private fun cartograph(input: ItemStack?, additional: ItemStack?, name: String? = null, lockedCopyId: Long? = null) =
        cartograph(input, additional, name, maps, lockedCopyId)

    @Test
    fun `a map item keeps which map it shows, the number its name shows, its scale, and whether it shows players, as a locator map`() {
        val stack = ItemStack(filledMap).showing(MapData(7, scale = 2, centreX = 0, centreZ = 0, showsPlayers = true, nameIndex = 3))
        assertEquals(7L, stack.mapId)
        assertEquals(LOCATOR, stack.aux)
        assertEquals(3, stack.nbt.getInt("map_name_index"))
        assertEquals(2.toByte(), stack.nbt.getByte("map_scale"))
        assertEquals(1.toByte(), stack.nbt.getByte("map_display_players"))
        assertNull(ItemStack(emptyMap).mapId)
        assertEquals(listOf(1, 2), listOf(maps.newNameIndex(), maps.newNameIndex()))
    }

    @Test
    fun `a cartography table copies a map onto an empty map, making two of it`() {
        val made = cartograph(mapItem(map(), count = 3), ItemStack(emptyMap, 5))!!
        assertEquals(mapItem(map(), count = 2), made.output)
        assertEquals(listOf(1, 1), listOf(made.inputUsed, made.additionalUsed))
        assertEquals(emptyList(), made.newMaps)
    }

    @Test
    fun `paper zooms a map out a step to its parent, still named as it was, up to the fourth, and never a locked one`() {
        val map = maps.start(0, 0)
        val made = cartograph(ItemStack(filledMap).showing(map), ItemStack(paper, 8))!!
        val parent = maps[map.parentId!!]!!
        assertEquals(parent.id, made.output.mapId)
        assertEquals(1.toByte(), made.output.nbt.getByte("map_scale"))
        assertEquals(map.nameIndex, made.output.nbt.getInt("map_name_index"))
        assertEquals(emptyList(), made.newMaps)

        val furthest = maps[maps.lineage(map).last()]!!
        assertNull(cartograph(ItemStack(filledMap).showing(furthest), ItemStack(paper)))
        assertNull(cartograph(mapItem(map(id = 3, locked = true)), ItemStack(paper)))
    }

    @Test
    fun `a glass pane locks a map as a copy with all that's drawn on it, the client's if it numbered one, and a compass makes it a locator map`() {
        val drawn = maps.start(0, 0).also { it[5, 5] = plainsGrass }
        val item = ItemStack(filledMap).showing(drawn)
        val locked = cartograph(item, ItemStack(glassPane))!!.newMaps.single()
        assertTrue(locked.locked)
        assertEquals(drawn.nameIndex, locked.nameIndex)
        assertNull(locked.parentId)
        assertContentEquals(drawn.pixels, locked.pixels)

        // The copy the client numbered as the pane went in, which is made then.
        maps.add(drawn.lockedCopy(1971533702))
        val numbered = cartograph(item, ItemStack(glassPane), lockedCopyId = 1971533702)!!
        assertEquals(1971533702L, numbered.output.mapId)
        assertEquals(emptyList(), numbered.newMaps)

        maps.add(locked)
        assertNull(cartograph(ItemStack(filledMap).showing(locked), ItemStack(glassPane)))
        // A locked map still copies.
        assertNotNull(cartograph(ItemStack(filledMap).showing(locked), ItemStack(emptyMap)))

        val locator = cartograph(item, ItemStack(compass))!!
        val locatorMap = locator.newMaps.first()
        assertEquals(5, locator.newMaps.size, "with parents of its own")
        assertTrue(locator.newMaps.all { it.showsPlayers && it.nameIndex == drawn.nameIndex })
        assertContentEquals(drawn.pixels, locatorMap.pixels)
        assertEquals(LOCATOR, locator.output.aux)
        locator.newMaps.forEach(maps::add)
        assertNull(cartograph(ItemStack(filledMap).showing(locatorMap), ItemStack(compass)))

        val emptyLocator = cartograph(ItemStack(emptyMap, 4), ItemStack(compass))!!
        assertEquals(ItemStack(emptyMap, aux = LOCATOR), emptyLocator.output)
    }

    @Test
    fun `naming at a cartography table names the whole stack, and goes with whatever else it does`() {
        val named = cartograph(mapItem(map(), count = 3), additional = null, name = "Home")!!
        assertEquals("Home", named.output.customName)
        assertEquals(listOf(3, 3, 0), listOf(named.output.count, named.inputUsed, named.additionalUsed))
        assertEquals("Base", cartograph(mapItem(map()), ItemStack(emptyMap), name = "Base")!!.output.customName)

        // Nothing to do: no name, or the one it has.
        assertNull(cartograph(mapItem(map()), additional = null))
        assertNull(cartograph(mapItem(map()).withCustomName("Home"), additional = null, name = "Home"))
        // And nothing for a map nobody drew, or something that isn't a map.
        assertNull(cartograph(ItemStack(filledMap).showing(map(id = 42)), ItemStack(emptyMap)))
        assertNull(cartograph(ItemStack(paper), ItemStack(compass)))
        assertNull(cartograph(mapItem(map()), mapItem(map(id = 2))), "another map beside it makes nothing")
        assertTrue(listOf(mapItem(map()), ItemStack(emptyMap), ItemStack(paper), ItemStack(glassPane), ItemStack(compass)).all { it.fitsCartographyTable })
    }

    @Test
    fun `a cartography table works a map in either slot, with what works it in the other`() {
        val map = maps.start(0, 0)
        val locked = cartograph(ItemStack(glassPane, 3), ItemStack(filledMap).showing(map))!!
        assertTrue(locked.newMaps.single().locked)
        assertEquals(listOf(1, 1), listOf(locked.inputUsed, locked.additionalUsed))

        val zoomed = cartograph(ItemStack(paper, 5), ItemStack(filledMap).showing(map))!!
        assertEquals(map.parentId, zoomed.output.mapId)

        val copied = cartograph(ItemStack(emptyMap, 4), ItemStack(filledMap, 2).showing(map))!!
        assertEquals(ItemStack(filledMap, 2).showing(map), copied.output, "copying the filled map onto the empty one")

        val emptyLocator = cartograph(ItemStack(compass), ItemStack(emptyMap, 2))!!
        assertEquals(ItemStack(emptyMap, aux = LOCATOR), emptyLocator.output)
        assertNull(cartograph(ItemStack(compass), ItemStack(glassPane)))
        assertNull(cartograph(null, ItemStack(compass)))
    }
}
