package org.koremc.registry

/**
 * A biome as the client's biome definitions describe it: the climate and
 * colours it renders with. How it generates is left out, since the client
 * doesn't generate terrain for Kore.
 */
data class BiomeDefinition(
    /** Its identifier, like `minecraft:plains`. */
    val name: String,
    /** Set only for custom biomes. The game's own server sends vanilla biomes without one. */
    val id: Int?,
    val temperature: Float,
    val downfall: Float,
    /** How far foliage has turned white with snow, from 0 to 1. */
    val foliageSnow: Float,
    val depth: Float,
    val scale: Float,
    /** The colour of water on maps, as ARGB. */
    val mapWaterColor: Int,
    val rain: Boolean,
    /** Groups the biome belongs to, like `ocean` or `monster`, which the game's rules refer to. */
    val tags: List<String>,
)
