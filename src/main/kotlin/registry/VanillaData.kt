package org.koremc.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.readText

private val json = Json { ignoreUnknownKeys = true }

/**
 * The game's own definitions, which clients expect the server to agree with:
 * every item, block state and biome. They're dumped from the game by
 * CloudburstMC, kept apart from Kore's code in https://github.com/KoreMC/data,
 * and read once, on start.
 */
data class VanillaData(val items: List<ItemDefinition>, val blocks: BlockPalette, val biomes: List<BiomeDefinition>) {
    companion object {
        /**
         * Reads the data from [directory], which holds the files from KoreMC/data
         * under CloudburstMC's names. A missing file throws [java.nio.file.NoSuchFileException].
         */
        fun load(directory: Path) = VanillaData(
            items = readItems(
                itemStates = directory.resolve("runtime_item_states.json").readText(),
                components = readNbt(directory.resolve("item_components.nbt")),
            ),
            blocks = readBlockPalette(readNbt(directory.resolve("block_palette.nbt"))),
            biomes = readBiomes(directory.resolve("stripped_biome_definitions.json").readText()),
        )
    }
}

/** An entry in `runtime_item_states.json`. */
@Serializable
private class ItemState(val name: String, val id: Int, val version: Int, val componentBased: Boolean)

/**
 * Reads the item list from `runtime_item_states.json`, giving each item its
 * entry from `item_components.nbt`, which is keyed by item name.
 */
fun readItems(itemStates: String, components: NbtMap): List<ItemDefinition> {
    val items = json.decodeFromString<List<ItemState>>(itemStates).map { item ->
        ItemDefinition(
            name = item.name,
            runtimeId = item.id,
            componentBased = item.componentBased,
            version = ItemVersion.entries.getOrNull(item.version) ?: error("${item.name} has unknown item version ${item.version}"),
            components = components.getCompound(item.name, NbtMap.EMPTY),
        )
    }
    items.find { it.runtimeId !in Short.MIN_VALUE..Short.MAX_VALUE }?.let { error("${it.name}'s runtime ID ${it.runtimeId} doesn't fit in 16 bits") }
    items.checkUnique("item names", ItemDefinition::name)
    items.checkUnique("item runtime IDs", ItemDefinition::runtimeId)
    return items
}

/** Reads every block state from `block_palette.nbt`, which lists them as `{name, states, version}` compounds under `blocks`. */
fun readBlockPalette(palette: NbtMap): BlockPalette {
    val blocks = palette.getList("blocks", NbtType.COMPOUND)
    check(blocks.isNotEmpty()) { "The block palette has no blocks" }

    return BlockPalette(blocks.map { block ->
        val name = block.getString("name").ifEmpty { error("A block state in the palette has no name") }
        BlockState(name, block.getCompound("states"), block.getInt("version"))
    })
}

/** An entry in `stripped_biome_definitions.json`, which keys them by name. Fields the protocol has since dropped are ignored. */
@Serializable
private class BiomeEntry(
    val id: Int? = null,
    val temperature: Float,
    val downfall: Float,
    val foliageSnow: Float,
    val depth: Float,
    val scale: Float,
    val mapWaterColor: Argb,
    val rain: Boolean,
    val tags: List<String>? = null,
)

@Serializable
private class Argb(val a: Int, val r: Int, val g: Int, val b: Int) {
    fun pack() = (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Reads every biome from `stripped_biome_definitions.json`: the definitions
 * the game sends, without the world generation data that only a client
 * generating its own terrain needs.
 */
fun readBiomes(definitions: String): List<BiomeDefinition> {
    val biomes = json.decodeFromString<Map<String, BiomeEntry>>(definitions).map { (name, biome) ->
        BiomeDefinition(
            name = name,
            id = biome.id,
            temperature = biome.temperature,
            downfall = biome.downfall,
            foliageSnow = biome.foliageSnow,
            depth = biome.depth,
            scale = biome.scale,
            mapWaterColor = biome.mapWaterColor.pack(),
            rain = biome.rain,
            tags = biome.tags.orEmpty(),
        )
    }
    check(biomes.isNotEmpty()) { "There are no biome definitions" }
    // 0xFFFF is what a biome without an ID goes out as, so no biome can have it.
    biomes.find { it.id != null && it.id !in 0 until 0xFFFF }?.let { error("${it.name}'s ID ${it.id} isn't between 0 and 65534") }
    biomes.mapNotNull { it.id }.checkUnique("biome IDs") { it }
    return biomes
}

private fun <T> List<T>.checkUnique(what: String, key: (T) -> Any) {
    val repeated = groupingBy(key).eachCount().filterValues { it > 1 }.keys
    check(repeated.isEmpty()) { "Repeated $what: ${repeated.take(5)}" }
}

/** Reads one of Cloudburst's NBT files: gzipped, big-endian, with a compound at the root. */
private fun readNbt(file: Path): NbtMap =
    NbtUtils.createGZIPReader(file.inputStream()).use { it.readTag() } as? NbtMap ?: error("$file doesn't hold an NBT compound")
