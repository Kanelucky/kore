package org.koremc.storage

import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.world.BlockPos
import org.koremc.world.MIN_Y
import org.koremc.world.PLAINS_BIOME_ID

// The biomes the presets are in, by the numbers chunks name them with.
const val DESERT_BIOME_ID = 2
const val WINDSWEPT_HILLS_BIOME_ID = 3
const val SNOWY_PLAINS_BIOME_ID = 12
const val DEEP_OCEAN_BIOME_ID = 24

/** The names of the biomes Kore knows the numbers of, by number. */
val BIOME_NAMES = mapOf(
    PLAINS_BIOME_ID to "minecraft:plains",
    DESERT_BIOME_ID to "minecraft:desert",
    WINDSWEPT_HILLS_BIOME_ID to "minecraft:extreme_hills",
    SNOWY_PLAINS_BIOME_ID to "minecraft:ice_plains",
    DEEP_OCEAN_BIOME_ID to "minecraft:deep_ocean",
)

private const val GRASS = "minecraft:grass_block"
private const val DIRT = "minecraft:dirt"
private const val STONE = "minecraft:stone"
private const val BEDROCK = "minecraft:bedrock"
private const val SAND = "minecraft:sand"
private const val SANDSTONE = "minecraft:sandstone"
private const val COBBLESTONE = "minecraft:cobblestone"

/** How far The Void's platform reaches each way from the cobblestone in its middle. */
private const val PLATFORM_REACH = 16

/**
 * The game's flat world presets, as it offers them for a new world: each
 * by its [title], as the game names it in English, and in American English
 * too where that's spelt otherwise, [usTitle], the [layers] it's made of,
 * as the game lists them, from the top down, by block and how many deep,
 * and the [biome] it's in.
 */
enum class FlatPreset(val title: String, private val biome: Int, private vararg val layers: Pair<String, Int>, private val usTitle: String? = null) {
    CLASSIC_FLAT("Classic Flat", PLAINS_BIOME_ID, GRASS to 1, DIRT to 2, BEDROCK to 1),
    TUNNELLERS_DREAM("Tunneller's Dream", WINDSWEPT_HILLS_BIOME_ID, GRASS to 1, DIRT to 5, STONE to 230, BEDROCK to 1, usTitle = "Tunneler's Dream"),
    WATER_WORLD(
        "Water World", DEEP_OCEAN_BIOME_ID,
        "minecraft:water" to 90, "minecraft:gravel" to 5, DIRT to 5, STONE to 5, "minecraft:deepslate" to 64, BEDROCK to 1,
    ),
    OVERWORLD("Overworld", PLAINS_BIOME_ID, GRASS to 1, DIRT to 3, STONE to 59, BEDROCK to 1),
    SNOWY_KINGDOM("Snowy Kingdom", SNOWY_PLAINS_BIOME_ID, "minecraft:snow_layer" to 1, GRASS to 1, DIRT to 3, STONE to 59, BEDROCK to 1),
    BOTTOMLESS_PIT("Bottomless Pit", PLAINS_BIOME_ID, GRASS to 1, DIRT to 3, COBBLESTONE to 2),
    DESERT("Desert", DESERT_BIOME_ID, SAND to 8, SANDSTONE to 52, STONE to 3, BEDROCK to 1),
    REDSTONE_READY("Redstone Ready", DESERT_BIOME_ID, SANDSTONE to 116, STONE to 3, BEDROCK to 1),

    /** Nothing but air, besides a platform of stone at the bottom of the world, around where players start, on cobblestone. */
    THE_VOID("The Void", PLAINS_BIOME_ID, AIR to 1),
    ;

    /** What a new world of this preset is made of, by [blocks]. */
    fun layers(blocks: BlockPalette): FlatLayers {
        val states = layers.reversed().flatMap { (name, count) -> List(count) { blocks.defaultOf(name) ?: error("No block $name for $title") } }
        val platform = if (this != THE_VOID) emptyMap() else buildMap {
            val stone = blocks.defaultOf(STONE) ?: error("No block $STONE for $title")
            for (x in -PLATFORM_REACH..PLATFORM_REACH) for (z in -PLATFORM_REACH..PLATFORM_REACH) put(BlockPos(x, MIN_Y, z), stone)
            put(BlockPos(0, MIN_Y, 0), blocks.defaultOf(COBBLESTONE) ?: error("No block $COBBLESTONE for $title"))
        }
        return FlatLayers(states, biome, platform)
    }

    companion object {
        /** The preset the game calls [name], spelt and capitalised as it spells it, or null for none. */
        fun named(name: String): FlatPreset? = entries.find { it.title == name || it.usTitle == name }
    }
}
