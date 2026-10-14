package org.koremc.storage

import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtMapBuilder
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.world.BlockPos
import org.koremc.world.GAME_RULE_DEFAULTS
import org.koremc.world.GameRules
import org.koremc.world.PLAINS_BIOME_ID
import org.koremc.world.Weather
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readBytes

/** The level.dat format the game writes now. */
private const val STORAGE_VERSION = 10

/** The game's generators, as level.dat numbers them. */
const val LEGACY_GENERATOR = 0
const val INFINITE_GENERATOR = 1
const val FLAT_GENERATOR = 2

/** The kinds of world the game makes, by the names its dedicated server gives them. */
enum class LevelType {
    /** Worlds that go on for ever, with hills, caves and oceans. */
    DEFAULT,

    /** Superflat worlds. */
    FLAT,

    /** Old worlds, of a limited size. */
    LEGACY,
}

/** The height the game saves as a spawn's, for one it puts on the ground wherever that is. */
private const val GROUND = Short.MAX_VALUE.toInt()

/** How the game writes a flat world's layers, for the version of them that names blocks. */
private const val LAYERS_ENCODING = 6

/**
 * What a flat world is made of: its [layers], from the bottom of the world
 * up, and its [biome], and the blocks a new one is made with besides, its
 * [platform], which are saved as any others are.
 */
data class FlatLayers(val layers: List<BlockState>, val biome: Int = PLAINS_BIOME_ID, val platform: Map<BlockPos, BlockState> = emptyMap())

/**
 * A world's settings, as the game keeps them in its level.dat: little-
 * endian NBT, behind the format and the NBT's length, each a little-endian
 * int. Kore changes only the settings it has a say in, and keeps the rest
 * as the game left them.
 */
class LevelDat(val data: NbtMap) {
    val name: String get() = data.getString("LevelName", "")

    /** How the world's terrain is made: [LEGACY_GENERATOR], [INFINITE_GENERATOR] or [FLAT_GENERATOR]. */
    val generator: Int get() = data.getInt("Generator", FLAT_GENERATOR)

    val seed: Long get() = data.getLong("RandomSeed", 0)

    /** The game mode players start in, or null for one Kore doesn't know. */
    val gameMode: GameMode? get() = data.getInt("GameType", 0).let { id -> GameMode.entries.find { it.id == id } }

    /** Where players appear, or null for the game to put them on the ground at the spawn's x and z, as [groundSpawn] says. */
    val spawn: BlockPos? get() = BlockPos(data.getInt("SpawnX", 0), data.getInt("SpawnY", GROUND), data.getInt("SpawnZ", 0)).takeIf { it.y != GROUND }

    /** The x and z of a spawn the game puts on the ground. */
    val groundSpawn: Pair<Int, Int> get() = data.getInt("SpawnX", 0) to data.getInt("SpawnZ", 0)

    /**
     * What a flat world is made of, by [blocks], as its layers say, or null
     * if they're missing, or name a preset rather than blocks. The game's
     * old name for grass blocks, `minecraft:grass`, still makes them.
     */
    fun flatLayers(blocks: BlockPalette): FlatLayers? {
        val saved = data.getString("FlatWorldLayers", "").takeIf { it.isNotBlank() } ?: return null
        val json = runCatching { Json.parseToJsonElement(saved).jsonObject }.getOrNull() ?: return null
        val layers = json["block_layers"]?.jsonArray ?: return null
        val states = layers.flatMap { layer ->
            val name = layer.jsonObject["block_name"]?.jsonPrimitive?.content ?: return null
            val state = blocks.defaultOf(if (name == "minecraft:grass") GRASS_BLOCK else name) ?: return null
            List(layer.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 1) { state }
        }
        return FlatLayers(states, json["biome_id"]?.jsonPrimitive?.intOrNull ?: PLAINS_BIOME_ID)
    }

    /** The time of day, in ticks since the world began, and how long the world has run, whatever the time of day. */
    val time: Long get() = data.getLong("Time", 0)
    val gameTime: Long get() = data.getLong("currentTick", 0)

    /** The weather as the world was saved with it. */
    fun weather() = Weather().apply {
        raining = data.getFloat("rainLevel", 0f) > 0
        rainTime = data.getInt("rainTime", 0)
        thundering = data.getFloat("lightningLevel", 0f) > 0
        thunderTime = data.getInt("lightningTime", 0)
    }

    /** The game rules as the world was saved with them, by the names the game keeps them under: on or off, kept as a byte, or a number. */
    val gameRules: Map<String, Any>
        get() = GAME_RULE_DEFAULTS.keys.map(String::lowercase).mapNotNull { rule ->
            when (val value = data[rule]) {
                is Byte -> rule to (value != 0.toByte())
                is Int -> rule to value
                else -> null
            }
        }.toMap()

    /** These settings with [changes] made. */
    fun with(changes: NbtMapBuilder.() -> Unit) = LevelDat(data.toBuilder().apply(changes).build())

    /** These settings with the world's [time], [gameTime], [weather] and [rules] as they are now. */
    fun keeping(time: Long, gameTime: Long, weather: Weather, rules: GameRules) = with {
        putLong("Time", time).putLong("currentTick", gameTime)
        putFloat("rainLevel", if (weather.raining) 1f else 0f).putInt("rainTime", weather.rainTime)
        putFloat("lightningLevel", if (weather.thundering) 1f else 0f).putInt("lightningTime", weather.thunderTime)
        putRules(rules.all)
    }

    /** Writes the settings to [file], whole or not at all. */
    fun write(file: Path) {
        val nbt = Unpooled.buffer()
        NbtUtils.createWriterLE(ByteBufOutputStream(nbt)).writeTag(data)
        val out = Unpooled.buffer().writeIntLE(STORAGE_VERSION).writeIntLE(nbt.readableBytes()).writeBytes(nbt)
        val written = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(written, ByteBufUtil.getBytes(out))
        Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun read(file: Path): LevelDat {
            val input = Unpooled.wrappedBuffer(file.readBytes())
            input.skipBytes(8) // The format, and the length of what follows
            return LevelDat(NbtUtils.createReaderLE(ByteBufInputStream(input)).readTag() as NbtMap)
        }

        /**
         * The settings of a new flat world called [name], made of [layers],
         * spawning players at [spawn], for the client [version] speaking
         * [protocol]: the game's defaults, but for what Kore says.
         */
        fun create(name: String, layers: FlatLayers, spawn: BlockPos, gameMode: GameMode, difficulty: Difficulty, seed: Long, cheats: Boolean, version: String, protocol: Int) =
            LevelDat(defaults(name, layers, seed, version).build()).updated(spawn, gameMode, difficulty, cheats, version, protocol)
    }

    /**
     * These settings, as Kore last ran the world: players spawning at
     * [spawn], in [gameMode] by default, at [difficulty], with [cheats] or
     * not, last opened by the client [version] speaking [protocol], now.
     */
    fun updated(spawn: BlockPos, gameMode: GameMode, difficulty: Difficulty, cheats: Boolean, version: String, protocol: Int) = with {
        val versionParts = (version.split('.').mapNotNull(String::toIntOrNull) + listOf(0, 0)).take(5)
        putInt("SpawnX", spawn.x).putInt("SpawnY", spawn.y).putInt("SpawnZ", spawn.z)
        putInt("GameType", gameMode.id)
        putInt("Difficulty", difficulty.ordinal)
        putBoolean("cheatsEnabled", cheats)
        putBoolean("commandsEnabled", cheats)
        putInt("NetworkVersion", protocol)
        putString("InventoryVersion", version)
        putList("lastOpenedWithVersion", NbtType.INT, versionParts)
        putInt("StorageVersion", STORAGE_VERSION)
        putLong("LastPlayed", System.currentTimeMillis() / 1000)
    }
}

private const val GRASS_BLOCK = "minecraft:grass_block"

/** The layers of a flat world as level.dat keeps them: JSON naming each run of the same block, from the bottom up. */
internal fun flatLayersJson(layers: FlatLayers): String {
    val runs = mutableListOf<Pair<String, Int>>()
    for (state in layers.layers) {
        val last = runs.lastOrNull()
        if (last != null && last.first == state.name) runs[runs.lastIndex] = last.first to last.second + 1 else runs += state.name to 1
    }
    return buildJsonObject {
        put("biome_id", layers.biome)
        put(
            "block_layers",
            buildJsonArray { runs.forEach { (name, count) -> add(buildJsonObject { put("block_name", name); put("count", count) }) } },
        )
        put("encoding_version", LAYERS_ENCODING)
        put("structure_options", JsonNull)
        put("world_version", "version.post_1_18")
    }.toString()
}

/** The game's defaults for a new flat world called [name], made of [layers], from [seed], for the client [version]. */
private fun defaults(name: String, layers: FlatLayers, seed: Long, version: String): NbtMapBuilder {
    val versionParts = (version.split('.').mapNotNull(String::toIntOrNull) + listOf(0, 0)).take(5)
    val data = NbtMap.builder()
        .putString("LevelName", name)
        .putInt("Generator", FLAT_GENERATOR)
        .putString("FlatWorldLayers", flatLayersJson(layers))
        .putLong("RandomSeed", seed)
        .putString("baseGameVersion", "*")
        .putList("MinimumCompatibleClientVersion", NbtType.INT, versionParts)
        .putInt("Platform", 2)
        .putLong("Time", 0)
        .putLong("currentTick", 0)
        .putLong("worldStartCount", 0)
        .putInt("NetherScale", 8)
        .putInt("WorldVersion", 1)
        .putInt("limitedWorldWidth", 16)
        .putInt("limitedWorldDepth", 16)
        .putBoolean("MultiplayerGame", true)
        .putBoolean("MultiplayerGameIntent", true)
        .putBoolean("LANBroadcast", true)
        .putBoolean("LANBroadcastIntent", true)
        .putInt("XBLBroadcastIntent", 3)
        .putInt("PlatformBroadcastIntent", 3)
        .putFloat("rainLevel", 0f)
        .putFloat("lightningLevel", 0f)
        .putInt("serverChunkTickRange", 4)
        .putCompound("experiments", NbtMap.EMPTY)
        .putCompound(
            "abilities",
            NbtMap.builder()
                .putBoolean("attackmobs", true).putBoolean("attackplayers", true).putBoolean("build", true).putBoolean("mine", true)
                .putBoolean("doorsandswitches", true).putBoolean("opencontainers", true).putBoolean("teleport", false)
                .putBoolean("flying", false).putBoolean("mayfly", false).putBoolean("instabuild", false).putBoolean("invulnerable", false)
                .putBoolean("lightning", false).putBoolean("op", false)
                .putFloat("flySpeed", 0.05f).putFloat("walkSpeed", 0.1f).putFloat("verticalFlySpeed", 1f)
                .putInt("permissionsLevel", 0).putInt("playerPermissionsLevel", 1)
                .build(),
        )
    // The game rules, at their defaults, and whether mobs spawn at all, which the game keeps beside them.
    data.putRules(GameRules().all)
    data.putBoolean("spawnMobs", true)
    return data
}

/** Puts [rules] in, by the names the game keeps them under: on or off as a byte, or a number. */
private fun NbtMapBuilder.putRules(rules: Map<String, Any>) {
    for ((rule, value) in rules) {
        when (value) {
            is Boolean -> putBoolean(rule, value)
            is Int -> putInt(rule, value)
        }
    }
}
