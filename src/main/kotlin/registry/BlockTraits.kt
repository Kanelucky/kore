package org.koremc.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How a biome tints a block, where the block's own colour doesn't settle
 * it: grass, leaves and water take the biome's colours, birch and spruce
 * leaves colours of their own, and redstone dust its power's.
 */
enum class Tint { NONE, GRASS, DEFAULT_FOLIAGE, BIRCH_FOLIAGE, EVERGREEN_FOLIAGE, DRY_FOLIAGE, WATER, STEM, REDSTONE_WIRE }

/** What the game says of one block state besides its name and properties, as far as Kore needs it. */
data class BlockTrait(
    /** The colour the block shows on maps, as ARGB, where a transparent one shows what's under it. */
    val mapColour: Int,
    val tint: Tint,
    /** How much of the light reaching the block it stops, from none to all of it, 15. */
    val lightDampening: Int,
)

/**
 * What the game says of every block state besides its name and
 * properties, by network ID: the colour it shows on maps, and how much
 * light it stops. A block state with nothing said shows nothing on maps,
 * and stops no light.
 */
class BlockTraits(private val byNetworkId: Map<Int, BlockTrait>) {
    operator fun get(networkId: Int): BlockTrait = byNetworkId[networkId] ?: NOTHING

    /** Whether the game says anything of the block state [networkId]. */
    operator fun contains(networkId: Int) = networkId in byNetworkId

    companion object {
        private val NOTHING = BlockTrait(0, Tint.NONE, 0)

        /** No block states at all, as in tests that don't need them. */
        val NONE = BlockTraits(emptyMap())
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** An entry in AllayMC's `block_states.json`, which says much more about each state than Kore reads. */
@Serializable
private class StateEntry(val blockStateHash: Long, val mapColor: String, val tintMethod: String, val lightDampening: Int)

/** The game's tint methods, by the names AllayMC's data gives them. */
private val TINTS = mapOf(
    "None" to Tint.NONE, "Grass" to Tint.GRASS, "DefaultFoliage" to Tint.DEFAULT_FOLIAGE, "BirchFoliage" to Tint.BIRCH_FOLIAGE,
    "EvergreenFoliage" to Tint.EVERGREEN_FOLIAGE, "DryFoliage" to Tint.DRY_FOLIAGE, "Water" to Tint.WATER, "Stem" to Tint.STEM,
    "RedStoneWire" to Tint.REDSTONE_WIRE,
)

/**
 * Reads AllayMC's `block_states.json`, which names each state by its
 * network ID, unsigned, and gives its map colour as `#rrggbbaa`.
 */
internal fun readBlockTraits(text: String): BlockTraits {
    val entries = json.decodeFromString<List<StateEntry>>(text)
    return BlockTraits(
        entries.associate { entry ->
            val rgba = entry.mapColor.removePrefix("#").toLong(16).toInt()
            val argb = (rgba ushr 8) or (rgba shl 24)
            entry.blockStateHash.toInt() to BlockTrait(argb, TINTS[entry.tintMethod] ?: Tint.NONE, entry.lightDampening)
        },
    )
}
