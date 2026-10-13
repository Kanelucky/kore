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
import org.koremc.world.PLAINS_BIOME_ID
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

/** The height the game saves as a spawn's, for one it puts on the ground wherever that is. */
private const val GROUND = Short.MAX_VALUE.toInt()

/** How the game writes a flat world's layers, for the version of them that names blocks. */
private const val LAYERS_ENCODING = 6

/** What a flat world is made of: its [layers], from the bottom of the world up, and its [biome]. */
data class FlatLayers(val layers: List<BlockState>, val biome: Int = PLAINS_BIOME_ID)

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

    /** These settings with [changes] made. */
    fun with(changes: NbtMapBuilder.() -> Unit) = LevelDat(data.toBuilder().apply(changes).build())

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
    // The game rules, at their defaults.
    for ((rule, on) in GAME_RULES) data.putBoolean(rule, on)
    data.putInt("functioncommandlimit", 10000).putInt("maxcommandchainlength", 65535).putInt("randomtickspeed", 1)
        .putInt("spawnradius", 5).putInt("playerssleepingpercentage", 100)
    return data
}

/** The game's rules that are on or off, as a new world has them. */
private val GAME_RULES = mapOf(
    "commandblockoutput" to true, "commandblocksenabled" to true, "dodaylightcycle" to true, "doentitydrops" to true,
    "dofiretick" to true, "doimmediaterespawn" to false, "doinsomnia" to true, "domobloot" to true, "domobspawning" to true,
    "dotiledrops" to true, "doweathercycle" to true, "drowningdamage" to true, "falldamage" to true, "firedamage" to true,
    "freezedamage" to true, "keepinventory" to false, "mobgriefing" to true, "naturalregeneration" to true, "pvp" to true,
    "respawnblocksexplode" to true, "sendcommandfeedback" to true, "showcoordinates" to false, "showdeathmessages" to true,
    "showtags" to true, "spawnMobs" to true, "tntexplodes" to true, "recipesunlock" to true, "dolimitedcrafting" to false,
    "tntexplosiondropdecay" to false, "projectilescanbreakblocks" to true, "showrecipemessages" to true, "showbordereffect" to true,
)
