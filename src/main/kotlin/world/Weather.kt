package org.koremc.world

import org.koremc.registry.BlockState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/** How long a day lasts, from sunrise to sunrise, in ticks. */
const val DAY_LENGTH = 24000

// How long each kind of weather lasts by itself, in ticks: rain half a day to a day, and clear skies half a day to seven and a half.
private val RAIN_LASTS = 12000..24000
private val CLEAR_LASTS = 12000..180000
private val THUNDER_LASTS = 3600..15600
private val CALM_LASTS = 12000..180000

/** How many chunks around each player the sky reaches: rain filling cauldrons, and lightning, as the world ticks them. */
const val SKY_TICK_RANGE = 4

/** The chance of lightning striking each chunk around a player each tick of a thunderstorm. */
private const val LIGHTNING_CHANCE = 1.0 / 100_000

/** The chance each chunk around a player has rain fall on a block each tick, and of rain adding to a cauldron it falls in. */
private const val PRECIPITATION_CHANCE = 1.0 / 16
private const val CAULDRON_FILL_CHANCE = 0.05

/** How long a lightning bolt shows, in ticks. */
private const val BOLT_LIFETIME = 20

/** How far a lightning rod draws lightning from, in blocks. */
private const val ROD_REACH = 64

/** How long a struck lightning rod gives power, in ticks. */
internal const val ROD_POWER_TICKS = 8

/** How often daylight detectors look at the sky, in ticks. */
private const val DAYLIGHT_PERIOD = 20

private const val LIGHTNING_ROD = "lightning_rod"
private const val ROD_POWERED = "powered_bit"

/** The particles of copper being scraped, as lightning cleans it. */
private const val SCRAPE_PARTICLES = 3002

/**
 * The world's weather: whether it's [raining], and [thundering] with it,
 * and how long until each starts or stops by itself, in ticks, which the
 * game counts down only while the weather changes by itself.
 */
class Weather {
    var raining = false
    var thundering = false
    var rainTime = 0
    var thunderTime = 0
}

/** A lightning bolt that struck at [position], as the entity [id], which shows until it's gone, [BOLT_LIFETIME] ticks on. */
class LightningBolt(val id: Long, val position: Vec3) {
    internal var age = 0
}

/**
 * What the sky did in a tick: whether rain or thunder started or stopped,
 * the lightning bolts that struck, and where blocks changed, with what
 * happened there, like copper scraped clean by lightning.
 */
class SkyChanges(val weatherChanged: Boolean, val strikes: List<LightningBolt>, val changed: List<BlockPos>, val events: List<WorldEvent>)

/**
 * Ticks the sky: the world's time passes, if day turns to night, and the
 * weather changes in its own time, if it does. In a thunderstorm,
 * lightning strikes now and then in the chunks around [players], by their
 * chunks, drawn to a lightning rod nearby. Rain fills the cauldrons it
 * falls in, now and then, and daylight detectors look at the sky once a
 * second. Run once a tick.
 */
fun FlatWorld.tickSky(players: Collection<ChunkPos>, random: Random = Random.Default): SkyChanges {
    gameTime++
    if (rules.doDaylightCycle) time++
    val weatherChanged = rules.doWeatherCycle && changeWeather(random)
    for (bolt in bolts.values.toList()) if (++bolt.age >= BOLT_LIFETIME) bolts.remove(bolt.id)

    val strikes = mutableListOf<LightningBolt>()
    val changed = mutableListOf<BlockPos>()
    val events = mutableListOf<WorldEvent>()
    if (weather.raining) {
        for (chunk in players.flatMap { chunksAround(it, SKY_TICK_RANGE) }.distinct()) {
            if (weather.thundering && random.nextDouble() < LIGHTNING_CHANCE) strikes += strike(randomTop(chunk, random), changed, events, random)
            if (random.nextDouble() < PRECIPITATION_CHANCE) rainOn(randomTop(chunk, random).side(Face.DOWN), random)?.let(changed::add)
        }
    }
    if (gameTime % DAYLIGHT_PERIOD == 0L) changed += senseDaylight()
    return SkyChanges(weatherChanged, strikes, changed, events)
}

/**
 * Counts down to the next change of weather, and makes it once it comes:
 * rain starts or stops, and thunder, each for a while of its own. Returns
 * whether anything changed.
 */
private fun FlatWorld.changeWeather(random: Random): Boolean {
    var changed = false
    // Out of time, each picks how long its next spell lasts, the tick after it changes.
    if (weather.thunderTime > 0) {
        if (--weather.thunderTime == 0) {
            weather.thundering = !weather.thundering
            changed = weather.raining
        }
    } else {
        weather.thunderTime = random.nextInt(if (weather.thundering) THUNDER_LASTS else CALM_LASTS)
    }
    if (weather.rainTime > 0) {
        if (--weather.rainTime == 0) {
            weather.raining = !weather.raining
            changed = true
        }
    } else {
        weather.rainTime = random.nextInt(if (weather.raining) RAIN_LASTS else CLEAR_LASTS)
    }
    return changed
}

/** [range] picked from at random by [this]. */
private fun Random.nextInt(range: IntRange) = nextInt(range.first, range.last + 1)

/**
 * Sets the weather, as the `/weather` command does: [raining], and
 * [thundering] with it, for [ticks], or a while picked at random. Thunder
 * always comes with rain.
 */
fun FlatWorld.setWeather(raining: Boolean, thundering: Boolean, ticks: Int? = null, random: Random = Random.Default) {
    val lasts = ticks ?: random.nextInt(6000, 18001)
    weather.raining = raining || thundering
    weather.thundering = thundering
    weather.rainTime = lasts
    weather.thunderTime = lasts
}

/** The space above the top block of a column of the chunk at [chunk], picked at random, where rain and lightning fall. */
private fun FlatWorld.randomTop(chunk: ChunkPos, random: Random): BlockPos = topOf(chunk.x * 16 + random.nextInt(16), chunk.z * 16 + random.nextInt(16))

/** The space above the highest block at [x], [z] that anything falling lands on: a solid block, or liquid. */
internal fun FlatWorld.topOf(x: Int, z: Int): BlockPos {
    val chunk = chunkAt(BlockPos(x, 0, z).chunk)
    for (y in MIN_Y + chunk.subChunks.size * 16 - 1 downTo MIN_Y) {
        val state = stateAt(BlockPos(x, y, z)) ?: continue
        if (state.solidHeights() != null || state.liquid != null) return BlockPos(x, y + 1, z)
    }
    return BlockPos(x, MIN_Y, z)
}

/**
 * Strikes lightning at [pos], or at the nearest lightning rod that draws
 * it there. A rod it strikes gives power for a moment, and copper it
 * strikes, or a rod's on, is scraped clean, with copper around it a stage
 * cleaner here and there, where blocks change going in [changed] and the
 * particles in [events]. Returns the bolt.
 */
internal fun FlatWorld.strike(pos: BlockPos, changed: MutableList<BlockPos>, events: MutableList<WorldEvent>, random: Random = Random.Default): LightningBolt {
    val target = lightningRodNear(pos)?.side(Face.UP) ?: pos
    val bolt = LightningBolt(newEntityId(), target.bottomCentre)
    bolts[bolt.id] = bolt
    val struck = target.side(Face.DOWN)
    val state = stateAt(struck) ?: return bolt
    if (LIGHTNING_ROD in state.name) {
        blocks.find(state.name, state.properties + (ROD_POWERED to bit(true)))?.let { set(struck, it); changed += struck }
        schedule(struck, ROD_POWER_TICKS, BlockUpdate.ROD)
    }
    cleanCopper(struck, changed, events, random)
    return bolt
}

/**
 * Scrapes the copper lightning struck at [pos] clean, or the copper the
 * lightning rod there is on, and the rod itself, and then cleans copper
 * around it, a stage at a time, along a few short random walks from it.
 */
private fun FlatWorld.cleanCopper(pos: BlockPos, changed: MutableList<BlockPos>, events: MutableList<WorldEvent>, random: Random) {
    val state = stateAt(pos) ?: return
    val start = if (LIGHTNING_ROD in state.name) {
        lessWeathered(state, whole = true)?.let { set(pos, it); changed += pos }
        rodFacing(pos)?.let { pos.side(it.opposite) } ?: return
    } else {
        pos
    }
    val copper = stateAt(start)?.takeIf { weathers(it) } ?: return
    lessWeathered(copper, whole = true)?.let { set(start, it); changed += start }
    repeat(random.nextInt(3, 6)) {
        var at = start
        for (step in 1..random.nextInt(1, 9)) at = cleanStep(at, changed, events, random) ?: break
    }
}

/**
 * Takes a stage of weathering off a copper block beside [pos], or at a
 * corner of it, picked at random from a few tries, with the particles of
 * its scraping. Returns where it was, or null if none of those tried was
 * copper that weathered.
 */
private fun FlatWorld.cleanStep(pos: BlockPos, changed: MutableList<BlockPos>, events: MutableList<WorldEvent>, random: Random): BlockPos? {
    repeat(10) {
        val at = BlockPos(pos.x + random.nextInt(-1, 2), pos.y + random.nextInt(-1, 2), pos.z + random.nextInt(-1, 2))
        val cleaner = stateAt(at)?.let { lessWeathered(it) } ?: return@repeat
        set(at, cleaner)
        changed += at
        events += WorldEvent(at, SCRAPE_PARTICLES, 0)
        return at
    }
    return null
}

/** The face of the lightning rod at [pos] that points away from what it's on. */
private fun FlatWorld.rodFacing(pos: BlockPos): Face? = (stateAt(pos)?.properties?.get(FACING_DIRECTION) as? Int)?.let(Face::of)

/**
 * The nearest lightning rod within [ROD_REACH] blocks of [pos] that's the
 * highest block in its column, with nothing above it that anything would
 * land on, or null for none.
 */
private fun FlatWorld.lightningRodNear(pos: BlockPos): BlockPos? {
    val rods = blocks.states.filter { LIGHTNING_ROD in it.name }.mapTo(HashSet()) { it.networkId }
    var nearest: BlockPos? = null
    var nearestDistance = Long.MAX_VALUE
    val reach = ROD_REACH.toLong() * ROD_REACH
    for (chunkPos in chunksAround(pos.chunk, ROD_REACH / 16 + 1)) {
        val chunk = chunkAt(chunkPos)
        for ((index, storage) in chunk.subChunks.withIndex()) {
            if (storage.palette.none { it in rods }) continue
            for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                val at = BlockPos(chunkPos.x * 16 + x, MIN_Y + index * 16 + y, chunkPos.z * 16 + z)
                if (blockAt(at) !in rods) continue
                val distance = squared(at.x - pos.x) + squared(at.y - pos.y) + squared(at.z - pos.z)
                if (distance > reach || distance >= nearestDistance || !openAbove(at)) continue
                nearest = at
                nearestDistance = distance
            }
        }
    }
    return nearest
}

/** Whether nothing above [pos] would stop anything falling on it: no solid block, and no liquid. */
private fun FlatWorld.openAbove(pos: BlockPos): Boolean {
    val top = MIN_Y + chunkAt(pos.chunk).subChunks.size * 16 - 1
    return (pos.y + 1..top).none { y -> stateAt(BlockPos(pos.x, y, pos.z))?.let { it.solidHeights() != null || it.liquid != null } == true }
}

private fun squared(value: Int) = value.toLong() * value

/** The middle of the bottom of this block's space. */
private val BlockPos.bottomCentre get() = Vec3(x + 0.5f, y.toFloat(), z + 0.5f)

/** Whether this is a lightning rod struck a moment ago, which gives power. */
internal val BlockState.rodPowered get() = LIGHTNING_ROD in name && properties[ROD_POWERED] == 1.toByte()

/** Lets the struck lightning rod at [pos], [state], stop giving power, its moment over. Returns whether it changed. */
internal fun FlatWorld.endRodPower(pos: BlockPos, state: BlockState): Boolean {
    if (!state.rodPowered) return false
    blocks.find(state.name, state.properties + (ROD_POWERED to bit(false)))?.let { set(pos, it) } ?: return false
    return true
}

/**
 * Rain falling on the block at [pos], as it does now and then: into a
 * cauldron, empty or of water, it adds a level, now and then. Returns where
 * a cauldron filled, if one did.
 */
private fun FlatWorld.rainOn(pos: BlockPos, random: Random): BlockPos? {
    val state = stateAt(pos)?.takeIf { it.isCauldron } ?: return null
    if (random.nextDouble() >= CAULDRON_FILL_CHANCE || !rainFills(pos, state)) return null
    return pos
}

/** Every daylight detector, which the sky powers. Returns where they changed. */
private fun FlatWorld.senseDaylight(): List<BlockPos> {
    val changed = mutableListOf<BlockPos>()
    for (pos in daylightDetectors.toList()) {
        val state = stateAt(pos)?.takeIf { it.isDaylightDetector } ?: run { daylightDetectors -= pos; continue }
        val power = daylightPower(pos, state)
        if (state.properties[REDSTONE_SIGNAL] == power) continue
        blocks.find(state.name, state.properties + (REDSTONE_SIGNAL to power))?.let { set(pos, it); changed += pos }
    }
    return changed
}

/** Daylight detectors, which sense daylight, or night, inverted. */
internal const val DAYLIGHT_DETECTOR = "minecraft:daylight_detector"
internal const val INVERTED_DAYLIGHT_DETECTOR = "minecraft:daylight_detector_inverted"

val BlockState.isDaylightDetector get() = name == DAYLIGHT_DETECTOR || name == INVERTED_DAYLIGHT_DETECTOR

/**
 * The power the daylight detector at [pos], [state], gives: by how much
 * of the sky's light reaches it, highest at noon and less in rain and at
 * dawn and dusk, or, inverted, the more the darker it is.
 */
internal fun FlatWorld.daylightPower(pos: BlockPos, state: BlockState): Int {
    var power = skyLightAt(pos) - skyDarkening()
    if (state.name == INVERTED_DAYLIGHT_DETECTOR) return (MAX_POWER - power).coerceIn(0, MAX_POWER)
    if (power > 0) {
        var angle = sunAngle()
        // The sun's angle, drawn a fifth of the way towards noon, from whichever side.
        val towards = if (angle < PI) 0.0 else 2 * PI
        angle += (towards - angle) * 0.2
        power = (power * cos(angle)).roundToInt()
    }
    return power.coerceIn(0, MAX_POWER)
}

/** The sky's light at [pos]: all of it, under nothing but what lets light through, and none under anything else. */
private fun FlatWorld.skyLightAt(pos: BlockPos): Int {
    val top = MIN_Y + chunkAt(pos.chunk).subChunks.size * 16 - 1
    for (y in pos.y + 1..top) {
        val state = stateAt(BlockPos(pos.x, y, pos.z)) ?: continue
        if (state.isFullCube && !state.letsLightThrough) return 0
    }
    return MAX_POWER
}

/** How far through the day it is, from 0 at noon round to 1, as the sun moves, slower at noon and midnight. */
private fun FlatWorld.dayFraction(): Double {
    val fraction = (time % DAY_LENGTH) / DAY_LENGTH.toDouble() - 0.25
    val wrapped = fraction - floor(fraction)
    return (wrapped * 2 + (0.5 - cos(wrapped * PI) / 2)) / 3
}

/** The sun's angle, from 0 at noon round to 2π. */
private fun FlatWorld.sunAngle() = dayFraction() * 2 * PI

/** How much darker than full daylight the sky is, from 0 to 11: darker at night, and in rain and thunder. */
private fun FlatWorld.skyDarkening(): Int {
    val rain = 1 - (if (weather.raining) 5.0 else 0.0) / 16
    val thunder = 1 - (if (weather.thundering) 5.0 else 0.0) / 16
    val light = 0.5 + 2 * cos(sunAngle()).coerceIn(-0.25, 0.25)
    return ((1 - light * rain * thunder) * 11).toInt()
}
