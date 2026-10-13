package org.koremc.world

import org.koremc.protocol.MapMarker
import org.koremc.protocol.MapPatch
import org.koremc.registry.BlockTraits
import org.koremc.registry.ItemStack
import org.koremc.registry.Tint
import org.koremc.registry.customName
import org.koremc.registry.withCustomName
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

internal const val EMPTY_MAP = "minecraft:empty_map"
internal const val FILLED_MAP = "minecraft:filled_map"
private const val PAPER = "minecraft:paper"
private const val COMPASS = "minecraft:compass"
private const val GLASS_PANE = "minecraft:glass_pane"

/**
 * What a cartography table takes, in either of its slots: maps, empty or
 * not, and what works them: paper to zoom out, an empty map to copy onto,
 * a glass pane to lock, and a compass to show players.
 */
private val CARTOGRAPHY_ITEMS = setOf(FILLED_MAP, EMPTY_MAP, PAPER, GLASS_PANE, COMPASS)

/** The variant of an empty or filled map that shows where players are on it: a locator map. */
internal const val LOCATOR = 2

/** How many pixels a map has each way. */
const val MAP_SIZE = 128

/** The furthest a map zooms out: a pixel for every sixteen blocks each way. */
const val MAX_MAP_SCALE = 4

/** How far around the player holding a map it's drawn, in blocks. */
private const val DRAWN_AROUND = 128

/** How far off the map, in pixels from its middle, a player still shows as a dot on its edge. */
private const val OFF_MAP_REACH = 320

/** How much darker ground shows lower than the ground north of it, and how much brighter higher. */
private const val SHADE = 0.85

/** How deep water has to be to show nothing of the ground under it. */
private const val CLEAR_WATER = 15

/** How many chunks' worth of what maps show of them to keep, most recently drawn first. */
private const val KEPT_CHUNKS = 1024

// Where a map item keeps which map it is, and what kind, each as the game types them.
private const val MAP_ID = "map_uuid"
private const val NAME_INDEX = "map_name_index"
private const val MAP_SCALE = "map_scale"
private const val SHOWS_PLAYERS = "map_display_players"
private const val INITIALISED = "map_is_init"

/**
 * A map players drew, numbered [id]: [MAP_SIZE] pixels each way around
 * [centreX] and [centreZ], each covering 2^[scale] blocks each way. Its
 * [pixels] are ARGB, row by row, or 0 where nobody's explored yet. A
 * locator map shows where players are too, and a locked one never changes.
 * Its items are named by [nameIndex], as Map 1, Map 2 and so on.
 *
 * Every map but a locked one starts with one at each scale above it, its
 * parent, the next scale up, numbered [parentId]: the map it zooms out to.
 */
class MapData(
    val id: Long,
    val scale: Int,
    val centreX: Int,
    val centreZ: Int,
    val showsPlayers: Boolean = false,
    val locked: Boolean = false,
    val pixels: IntArray = IntArray(MAP_SIZE * MAP_SIZE),
    val nameIndex: Int = 0,
    val parentId: Long? = null,
) {
    /** The highest and lowest row that changed in each column since the map's viewers last saw, or -1 for none. */
    private val changedFrom = IntArray(MAP_SIZE) { -1 }
    private val changedTo = IntArray(MAP_SIZE) { -1 }

    /** The markers the map's viewers last saw. */
    internal var shownMarkers: List<MapMarker> = emptyList()

    /** Whether the map's been drawn on since it was last saved, or never saved. */
    internal var unsaved = true

    /** How many blocks each pixel covers, each way. */
    val blocksPerPixel get() = 1 shl scale

    operator fun get(x: Int, y: Int) = pixels[y * MAP_SIZE + x]

    /** Colours the pixel at [x], [y], unless it is already. Returns whether it changed. */
    operator fun set(x: Int, y: Int, colour: Int): Boolean {
        if (pixels[y * MAP_SIZE + x] == colour) return false
        pixels[y * MAP_SIZE + x] = colour
        unsaved = true
        changedFrom[x] = if (changedFrom[x] < 0) y else minOf(changedFrom[x], y)
        changedTo[x] = maxOf(changedTo[x], y)
        return true
    }

    /**
     * Every run of columns that changed since the map's viewers last saw,
     * each with the rows that changed in any of them, which they haven't
     * seen yet.
     */
    fun takeChanges(): List<MapPatch> {
        val patches = mutableListOf<MapPatch>()
        var x = 0
        while (x < MAP_SIZE) {
            if (changedFrom[x] < 0) {
                x++
                continue
            }
            val first = x
            var top = changedFrom[x]
            var bottom = changedTo[x]
            while (x < MAP_SIZE && changedFrom[x] >= 0) {
                top = minOf(top, changedFrom[x])
                bottom = maxOf(bottom, changedTo[x])
                changedFrom[x] = -1
                changedTo[x] = -1
                x++
            }
            patches += patch(first, top, x - first, bottom - top + 1)
        }
        return patches
    }

    /** The pixels in the square [width] by [height] from [x], [y]. */
    fun patch(x: Int = 0, y: Int = 0, width: Int = MAP_SIZE, height: Int = MAP_SIZE) =
        MapPatch(x, y, width, height, IntArray(width * height) { this[x + it % width, y + it / width] })

    /** This map as it is, as the map numbered [id], locked, which zooms out no further. */
    fun lockedCopy(id: Long) = MapData(id, scale, centreX, centreZ, showsPlayers, locked = true, pixels.copyOf(), nameIndex)

    /**
     * Where a player at [x], [z], looking [yaw] degrees round from south,
     * shows on the map: a pointer the way they look, or a dot on the edge
     * nearest them if they're off it, but not far off. Null if they're
     * further.
     */
    fun markerFor(x: Float, z: Float, yaw: Float): MapMarker? {
        val across = (x - centreX) / blocksPerPixel
        val down = (z - centreZ) / blocksPerPixel
        val edge = MAP_SIZE / 2 - 1f
        if (across in -edge..edge && down in -edge..edge) {
            val rotation = (yaw * 16 / 360).roundToInt() and 15
            return MapMarker(MapMarker.PLAYER, rotation, halfPixels(across), halfPixels(down))
        }
        if (abs(across) >= OFF_MAP_REACH || abs(down) >= OFF_MAP_REACH) return null
        fun clamped(at: Float) = when {
            at <= -edge -> -128
            at >= edge -> 127
            else -> halfPixels(at)
        }
        return MapMarker(MapMarker.PLAYER_OFF_MAP, 0, clamped(across), clamped(down))
    }

    private fun halfPixels(at: Float) = (at * 2 + 0.5f).toInt().coerceIn(-128, 127)
}

/**
 * The maps players have drawn, by number, and what they show of the world,
 * kept for each chunk until it changes.
 */
class Maps {
    private val byId = HashMap<Long, MapData>()

    /** How many maps have been named, which the next is named after. */
    private var named = 0

    /** What maps show of each column of a chunk, by the chunk as it is, which a change replaces with another. */
    private val tops = object : LinkedHashMap<Chunk, Array<MapSample?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Chunk, Array<MapSample?>>) = size > KEPT_CHUNKS
    }

    /** Every map. */
    val all: Collection<MapData> get() = byId.values

    operator fun get(id: Long): MapData? = byId[id]

    operator fun contains(id: Long) = id in byId

    /** Keeps [map], whose items' names count on from its. */
    fun add(map: MapData) {
        byId[map.id] = map
        named = maxOf(named, map.nameIndex)
    }

    /**
     * A new map at [scale] of the square around [x], [z], and its parents,
     * one for each scale up to the fourth, each named by [nameIndex], and
     * showing where players are if [showsPlayers]. The map comes first. None
     * of them are kept until they're [add]ed.
     */
    fun chain(x: Int, z: Int, scale: Int, showsPlayers: Boolean, nameIndex: Int): List<MapData> {
        val maps = mutableListOf<MapData>()
        for (at in MAX_MAP_SCALE downTo scale) {
            val (centreX, centreZ) = mapCentre(x, z, at)
            maps.add(0, MapData(newId(), at, centreX, centreZ, showsPlayers, nameIndex = nameIndex, parentId = maps.firstOrNull()?.id))
        }
        return maps
    }

    /** Starts a map at [scale] of the square around [x], [z], with its parents, named as the next new map is. Returns the map. */
    fun start(x: Int, z: Int, scale: Int = 0, showsPlayers: Boolean = false): MapData =
        chain(x, z, scale, showsPlayers, newNameIndex()).onEach(::add).first()

    /** The numbers of [map] and every map it zooms out to, its own first, as the client learns of them all with it. */
    fun lineage(map: MapData): List<Long> = generateSequence(map) { it.parentId?.let(byId::get) }.map { it.id }.toList()

    /**
     * A number for a new map. Clients remember the maps they've seen by
     * number, even from before the server last started, so new ones are
     * picked at random rather than counted.
     */
    fun newId(): Long = generateSequence { Random.nextLong() }.first { it != 0L && it != -1L && it !in byId }

    /** What the next new map's items are named after: Map 1 for the first. */
    fun newNameIndex(): Int = ++named

    /** What maps show of the columns of [chunk], filled in as they're drawn. */
    internal fun topsOf(chunk: Chunk): Array<MapSample?> = tops.getOrPut(chunk) { arrayOfNulls(16 * 16) }
}

/**
 * The middle of the map at [scale] that covers [x], [z]. Maps cover the
 * world in a grid at each scale, offset so the first at the smallest is
 * around 0, 0, so they line up however they were made.
 */
fun mapCentre(x: Int, z: Int, scale: Int): Pair<Int, Int> {
    val size = MAP_SIZE shl scale
    fun centre(at: Int) = Math.floorDiv(at + MAP_SIZE / 2, size) * size + size / 2 - MAP_SIZE / 2
    return centre(x) to centre(z)
}

/** Whether a cartography table takes [this], in either of its slots. */
val ItemStack.fitsCartographyTable: Boolean get() = item.name in CARTOGRAPHY_ITEMS

/** What a cartography table makes: the item, how many of what's in each of its slots that uses up, and the maps it starts, if any. */
class Cartographed(val output: ItemStack, val inputUsed: Int, val additionalUsed: Int, val newMaps: List<MapData> = emptyList())

/**
 * What a cartography table makes of the map or empty map in one of its
 * slots, [input] or [additional], with what's in the other, named [name]
 * if that's given, as Bedrock's do, finding maps in [maps], and numbering
 * new ones there. Null if it makes nothing. A filled map is the one worked
 * on, if there is one. Whatever it does to a map, it's still named as it was.
 *
 * Paper zooms a map out a step, up to the fourth, to its parent. An empty
 * map copies a map, making two of it. A glass pane locks a map, as a copy
 * that never changes: the one the client numbered [lockedCopyId] as the
 * pane went in, if it did, or a new one. A compass makes a map, or an
 * empty map, show where players are: a locator map, which for a map is a
 * new one, with parents of its own. A locked map can be copied, but not
 * zoomed out. Naming is free, and on its own names the whole stack. Paper
 * alone makes an empty map, which is a recipe of the table's own, not this.
 */
fun cartograph(input: ItemStack?, additional: ItemStack?, name: String?, maps: Maps, lockedCopyId: Long? = null): Cartographed? {
    val inBottom = input?.item?.name != FILLED_MAP && (additional?.item?.name == FILLED_MAP || (input?.item?.name != EMPTY_MAP && additional?.item?.name == EMPTY_MAP))
    if (!inBottom) return cartographMap(input ?: return null, additional, name, maps, lockedCopyId)
    val made = cartographMap(additional!!, input, name, maps, lockedCopyId) ?: return null
    return Cartographed(made.output, made.additionalUsed, made.inputUsed, made.newMaps)
}

/** What a cartography table makes of the map or empty map [input], with [additional] in its other slot, as [cartograph] says. */
private fun cartographMap(input: ItemStack, additional: ItemStack?, name: String?, maps: Maps, lockedCopyId: Long?): Cartographed? {
    val named = name?.takeIf { it.isNotBlank() && it != (input.customName ?: input.item.displayName) }
    if (additional == null && named == null) return null
    var newMaps = emptyList<MapData>()
    fun showing(map: MapData) = input.copy(count = 1).showing(map)
    val made = when (input.item.name) {
        FILLED_MAP -> {
            val map = input.mapId?.let(maps::get) ?: return null
            when (additional?.item?.name) {
                null -> input
                EMPTY_MAP -> input.copy(count = 2)
                PAPER -> if (map.locked) null else map.parentId?.let(maps::get)?.let(::showing)
                GLASS_PANE -> if (map.locked) null else {
                    lockedCopyId?.let(maps::get)?.takeIf { it.locked }?.let(::showing)
                        ?: showing(map.lockedCopy(maps.newId()).also { newMaps = listOf(it) })
                }
                COMPASS -> if (map.showsPlayers) null else {
                    newMaps = maps.chain(map.centreX, map.centreZ, map.scale, showsPlayers = true, map.nameIndex)
                    map.pixels.copyInto(newMaps.first().pixels)
                    showing(newMaps.first())
                }
                else -> null
            }
        }
        EMPTY_MAP -> when (additional?.item?.name) {
            null -> input
            COMPASS -> if (input.aux == LOCATOR) null else input.copy(count = 1, aux = LOCATOR)
            else -> null
        }
        else -> null
    } ?: return null
    val used = if (additional == null) input.count else 1
    return Cartographed(if (named != null) made.withCustomName(named) else made, used, if (additional == null) 0 else 1, newMaps)
}

/** The number of the map a map item shows, or null for one that shows none, like an empty map. */
val ItemStack.mapId: Long? get() = if (item.name == FILLED_MAP && nbt.containsKey(MAP_ID)) nbt.getLong(MAP_ID) else null

/**
 * This map item showing [map]: its number, the one its name shows, its
 * scale, and whether it shows players, a locator map's variant.
 */
fun ItemStack.showing(map: MapData): ItemStack = copy(
    aux = if (map.showsPlayers) LOCATOR else 0,
    nbt = nbt.toBuilder()
        .putLong(MAP_ID, map.id)
        .putInt(NAME_INDEX, map.nameIndex)
        .putByte(MAP_SCALE, map.scale.toByte())
        .putByte(SHOWS_PLAYERS, bit(map.showsPlayers))
        .putByte(INITIALISED, 1)
        .build(),
)

/**
 * The colours plants and water take on maps in a biome. Kore's worlds are
 * plains throughout, whose grass and leaves the game takes from its colour
 * maps, and whose water it defines.
 */
data class BiomeTints(val grass: Int, val foliage: Int, val dryFoliage: Int, val water: Int) {
    companion object {
        val PLAINS = BiomeTints(grass = 0x91BD59, foliage = 0x77AB2F, dryFoliage = 0xA37546, water = 0x44AFF5)
    }
}

/** Birch and spruce leaves, which are the same colour in every biome. */
private const val BIRCH_FOLIAGE = 0x80A755
private const val EVERGREEN_FOLIAGE = 0x619961

/** Redstone dust, which shows plain red, however powered. */
private const val REDSTONE = 0xFF0000

/** What a map shows of a column of the world: the colour of its top, or 0 for nothing, and how high that is. */
internal class MapSample(val colour: Int, val height: Int)

/** A column with nothing in it to show. */
private val NOTHING = MapSample(0, MIN_Y - 1)

/**
 * The top of the column at [x], [z] of [chunk] as a map shows it: the
 * first block down that shows anything, its colour tinted by [tints] where
 * the biome colours it, and under water, washed towards the water's colour
 * the deeper it is, until it's nothing but water.
 */
private fun sample(chunk: Chunk, x: Int, z: Int, traits: BlockTraits, tints: BiomeTints): MapSample {
    var water = 0
    var waterTop = 0
    for (y in MIN_Y + chunk.subChunks.size * 16 - 1 downTo MIN_Y) {
        val trait = traits[chunk.blockAt(BlockPos(x, y, z))]
        if (trait.tint == Tint.WATER) {
            if (water++ == 0) waterTop = y
            continue
        }
        val colour = when (trait.tint) {
            Tint.GRASS -> tints.grass
            Tint.DEFAULT_FOLIAGE -> tints.foliage
            Tint.DRY_FOLIAGE -> tints.dryFoliage
            Tint.BIRCH_FOLIAGE -> BIRCH_FOLIAGE
            Tint.EVERGREEN_FOLIAGE -> EVERGREEN_FOLIAGE
            Tint.REDSTONE_WIRE -> REDSTONE
            else -> if (trait.mapColour ushr 24 == 0) continue else trait.mapColour
        }
        if (water == 0) return MapSample(opaque(colour), y)
        return MapSample(opaque(underWater(colour, tints.water, water)), y)
    }
    return if (water > 0) MapSample(opaque(tints.water), waterTop) else NOTHING
}

/** The top of the column at [x], [z] as a map shows it, worked out once for each chunk as it is. */
private fun FlatWorld.top(x: Int, z: Int, traits: BlockTraits, tints: BiomeTints): MapSample {
    val chunk = chunkAt(ChunkPos(x shr 4, z shr 4))
    val tops = maps.topsOf(chunk)
    val column = (x and 0xF) * 16 + (z and 0xF)
    return tops[column] ?: sample(chunk, x, z, traits, tints).also { tops[column] = it }
}

/** [ground] seen under [depth] blocks of water coloured [water]: at least half the water's colour, all of it at [CLEAR_WATER]. */
private fun underWater(ground: Int, water: Int, depth: Int): Int {
    if (depth > CLEAR_WATER) return water
    val share = (depth / CLEAR_WATER.toFloat()).coerceAtLeast(0.5f)
    fun channel(shift: Int): Int {
        val from = ground shr shift and 0xFF
        return from + ((water shr shift and 0xFF) - from).times(share).toInt()
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or (water and 0xFF)
}

private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

/** [colour] darker by [SHADE], as ground lower than the ground north of it shows. */
private fun darker(colour: Int): Int = mapChannels(colour) { (it * SHADE).toInt() }

/** [colour] brighter by [SHADE], as ground higher than the ground north of it shows, lifting black a little first. */
private fun brighter(colour: Int): Int {
    val least = (1 / (1 - SHADE)).toInt()
    if ((colour and 0xFFFFFF) == 0) return (colour and (0xFF shl 24)) or (least shl 16) or (least shl 8) or least
    return mapChannels(colour) { channel -> (if (channel in 1 until least) least else channel).let { minOf((it / SHADE).toInt(), 255) } }
}

private inline fun mapChannels(colour: Int, change: (Int) -> Int): Int =
    (colour and (0xFF shl 24)) or (change(colour shr 16 and 0xFF) shl 16) or (change(colour shr 8 and 0xFF) shl 8) or change(colour and 0xFF)

/**
 * Draws [map] around a player holding it at [x], [z], as they explore:
 * the world's top, one block for each pixel, in a circle [DRAWN_AROUND]
 * blocks round them, all at once, with a ragged edge. Ground lower than
 * the ground north of it shows darker, and higher ground brighter. A
 * locked map draws nothing.
 */
fun FlatWorld.drawMap(map: MapData, x: Float, z: Float, traits: BlockTraits, tints: BiomeTints = BiomeTints.PLAINS) {
    if (map.locked) return
    val scale = map.blocksPerPixel
    val atX = Math.floorDiv(floor(x).toInt() - map.centreX, scale) + MAP_SIZE / 2
    val atZ = Math.floorDiv(floor(z).toInt() - map.centreZ, scale) + MAP_SIZE / 2
    val radius = DRAWN_AROUND / scale
    for (column in maxOf(atX - radius + 1, 0) until minOf(atX + radius, MAP_SIZE)) {
        val worldX = (map.centreX / scale + column - MAP_SIZE / 2) * scale
        var north: MapSample? = null
        // From a row above the first, for the ground north of it.
        for (row in maxOf(atZ - radius - 1, -1) until minOf(atZ + radius, MAP_SIZE)) {
            val here = top(worldX, (map.centreZ / scale + row - MAP_SIZE / 2) * scale, traits, tints)
            val above = north
            north = here
            val distance = (column - atX) * (column - atX) + (row - atZ) * (row - atZ)
            val edge = distance > (radius - 2) * (radius - 2)
            if (row < 0 || here.colour == 0 || distance >= radius * radius || (edge && ((column + row) and 1) == 0)) continue
            map[column, row] = when {
                above == null || above.colour == 0 || here.height == above.height -> here.colour
                here.height < above.height -> darker(here.colour)
                else -> brighter(here.colour)
            }
        }
    }
}
