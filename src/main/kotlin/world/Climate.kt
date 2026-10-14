package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BiomeDefinition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Below this temperature, it snows rather than rains, and water freezes. */
private const val COLD = 0.15f

/**
 * Above this height, it's colder the higher up, by [COOLING] each block.
 * The game moves the line up to eight blocks either way by a noise, which
 * Kore leaves out.
 */
private const val COOLS_ABOVE = 80
private const val COOLING = 0.05f / 40

/** The block light below which snow settles and water freezes. */
private const val FREEZING_LIGHT = 10

/** The most snow one block holds, in layers. */
private const val FULL_SNOW = 8

private const val ICE = "minecraft:ice"
private const val PACKED_ICE = "minecraft:packed_ice"

/** What falls from the sky while it rains: rain, snow where it's cold, or nothing where it's dry. */
enum class Precipitation { NONE, RAIN, SNOW }

/**
 * A biome's climate, as the game defines it: how warm it is,
 * [temperature], whether anything falls from its sky, [wet], how many
 * layers deep snow falling in it piles up, at most, [snowLayers], and how
 * deep the snow a world there starts with lies, at most, [snowCover]: none
 * for snow lying evenly, and more for it lying unevenly, in drifts and
 * patches, from none up to that.
 */
data class Climate(val temperature: Float, val wet: Boolean = true, val snowLayers: Int = 1, val snowCover: Int = 0) {
    companion object {
        /** Plains', the climate of a world in a biome Kore doesn't know. */
        val PLAINS = Climate(0.8f)

        /**
         * The snow in the biomes Kore makes worlds in where it's deeper than a
         * layer: how deep it piles up, and how deep a world starts with it, as
         * the game's own biome files have it, which Cloudburst's biome data
         * leaves out.
         */
        private val SNOW = mapOf("minecraft:ice_plains" to (8 to 2), "minecraft:extreme_hills" to (2 to 0))

        /** The climate of [biome], as the game defines it, or plains' for one Kore doesn't know. */
        fun of(biome: BiomeDefinition?): Climate {
            if (biome == null) return PLAINS
            val (layers, cover) = SNOW[biome.name] ?: (1 to 0)
            return Climate(biome.temperature, biome.rain, layers, cover)
        }
    }
}

/** The drifts of a world's snow: noise this many blocks wide, from broad to fine, each with its share. */
private val DRIFTS = listOf(10.0 to 0.55, 4.0 to 0.3, 1.7 to 0.15)

/** Where the drifts are this low, the ground is bare, and above each of these, the snow a layer deeper. */
private const val BARE_BELOW = -0.27
private val DEEPER_ABOVE = listOf(0.08, 0.3, 0.45, 0.55)

/**
 * How many layers deep the snow a world starts with lies at [x], [z], from
 * none up to [most]: in drifts, mostly a layer deep, deeper in some places
 * and bare in a few, laid out by the world's [seed], the same each time.
 */
internal fun snowDepth(seed: Long, x: Int, z: Int, most: Int): Int {
    val drift = DRIFTS.withIndex().sumOf { (octave, it) -> it.second * gradientNoise(seed + octave, x / it.first, z / it.first) }
    if (drift < BARE_BELOW) return 0
    return (1 + DEEPER_ABOVE.count { drift > it }).coerceAtMost(most)
}

/** Noise from about -0.7 to 0.7 that changes smoothly over [x] and [z], by [seed]: gradients at the corners of a grid, blended between. */
private fun gradientNoise(seed: Long, x: Double, z: Double): Double {
    val x0 = floor(x).toInt()
    val z0 = floor(z).toInt()
    fun corner(cx: Int, cz: Int): Double {
        val angle = cornerNoise(seed, cx, cz) * 2 * PI
        return cos(angle) * (x - cx) + sin(angle) * (z - cz)
    }
    val fx = fade(x - x0)
    val fz = fade(z - z0)
    val north = lerp(corner(x0, z0), corner(x0 + 1, z0), fx)
    val south = lerp(corner(x0, z0 + 1), corner(x0 + 1, z0 + 1), fx)
    return lerp(north, south, fz)
}

private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

private fun lerp(from: Double, to: Double, by: Double) = from + (to - from) * by

/** A number from 0 to 1 for the corner [x], [z] of the noise's grid, by [seed]. */
private fun cornerNoise(seed: Long, x: Int, z: Int): Double {
    var mixed = seed + x * -0x61c8864680b583ebL + z * 0x5851f42d4c957f2dL
    mixed = (mixed xor (mixed ushr 30)) * -0x40a7b892e31b1a47L
    mixed = (mixed xor (mixed ushr 27)) * -0x6b2fb644ecceee15L
    mixed = mixed xor (mixed ushr 31)
    return (mixed ushr 11) * (1.0 / (1L shl 53))
}

/** How warm it is at [pos]: the biome's temperature, and colder the higher above [COOLS_ABOVE]. */
internal fun FlatWorld.temperatureAt(pos: BlockPos): Float = climate.temperature - max(0, pos.y - COOLS_ABOVE) * COOLING

/** What falls at [pos] while it rains: snow where it's cold, rain where it isn't, and nothing in a dry biome. */
fun FlatWorld.precipitationAt(pos: BlockPos): Precipitation = when {
    !climate.wet -> Precipitation.NONE
    temperatureAt(pos) < COLD -> Precipitation.SNOW
    else -> Precipitation.RAIN
}

/**
 * The weather at the top of a column, [top], the space above its highest
 * block, as it comes to each chunk now and then: still water there
 * freezes where it's cold, whatever the weather, and while it rains, snow
 * settles where it's cold, and what falls fills a cauldron it falls in.
 * Snow and ice form only with growth on. Returns where blocks changed.
 */
internal fun FlatWorld.weatherOn(top: BlockPos, random: Random): List<BlockPos> {
    val changed = mutableListOf<BlockPos>()
    val below = top.side(Face.DOWN)
    if (features.growth && freezes(below)) blocks.defaultOf(ICE)?.let { set(below, it); changed += below }
    if (!weather.raining) return changed
    if (features.growth) snowOn(top)?.let(changed::add)
    rainOn(below, random)?.let(changed::add)
    return changed
}

/** Whether the water at [pos] freezes: still water, where it's cold, in dim light, not with water all round it. */
private fun FlatWorld.freezes(pos: BlockPos): Boolean {
    val state = stateAt(pos) ?: return false
    if (state.liquid != Liquid.WATER || state.liquidDepth != 0 || temperatureAt(pos) >= COLD) return false
    return blockLightAt(pos) < FREEZING_LIGHT && AROUND.any { !isWet(pos.side(it)) }
}

/**
 * Snow settling at the top of a column, [top], where it's cold, in dim
 * light: a layer on the ground there, if snow can lie on it, though not on
 * ice, or another on the snow there already, until it's as deep as the
 * biome piles it. Returns where it settled, if it did.
 */
private fun FlatWorld.snowOn(top: BlockPos): BlockPos? {
    // A single layer of snow is no ground for the next, so the top of the column can be the snow itself.
    val at = top.side(Face.DOWN).takeIf { stateAt(it)?.name == SNOW_LAYER } ?: top
    if (precipitationAt(at) != Precipitation.SNOW || blockLightAt(at) >= FREEZING_LIGHT) return null
    val state = stateAt(at) ?: return null
    val settled = when (state.name) {
        SNOW_LAYER -> {
            val height = state.number("height")
            if (height + 1 >= minOf(climate.snowLayers, FULL_SNOW)) return null
            blocks.find(SNOW_LAYER, state.properties + ("height" to height + 1))
        }
        AIR -> blocks.defaultOf(SNOW_LAYER)?.takeIf { snow ->
            stateAt(at.side(Face.DOWN))?.name !in setOf(ICE, PACKED_ICE) && survives(at, snow)
        }
        else -> null
    } ?: return null
    set(at, settled)
    return at
}

/** Whether rain is falling at [pos]: it's raining, it's rain rather than snow there, and nothing's above it. */
internal fun FlatWorld.rainingAt(pos: BlockPos): Boolean = weather.raining && precipitationAt(pos) == Precipitation.RAIN && topOf(pos.x, pos.z).y <= pos.y
