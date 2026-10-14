package org.koremc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koremc.world.Features
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = LoggerFactory.getLogger("org.koremc.KoreSettings")

/** Where Kore keeps the settings of its own, which the game's dedicated server doesn't have: beside server.properties. */
val KORE_JSON: Path = Path.of("kore.json")

/** Reads kore.json with the comments explaining each setting, as JSON with comments has them, and a comma after the last if there is one. */
@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    allowComments = true
    allowTrailingComma = true
}

/** The settings in kore.json: Kore's own, which server.properties, as the game's dedicated server has it, has no place for. */
@Serializable
private data class KoreSettings(
    val advertisedAddresses: List<String>,
    val operatorDomain: String,
    val shutdownMessage: String,
    val features: Features,
)

/** What each setting is for, written above it, and each of those within a setting, by the setting's name and its own. */
private val COMMENTS = mapOf(
    "advertisedAddresses" to "The addresses players reach the server at: IP addresses or host names.\n" +
        "Left empty, every address this computer has that others can reach.",
    "operatorDomain" to "Shown to players, as text the server can't vouch for, the first time they accept its key.",
    "shutdownMessage" to "What players still on the server are told as it stops.",
    "features" to "The game's systems Kore runs: all on, the game as it is, and all off, blocks that are only blocks.\n" +
        "Off, a system doesn't run at all, and its blocks do nothing. The game rules switch off the rest,\n" +
        "like the weather with doweathercycle, or blocks' drops with dotiledrops.",
    "features.redstone" to "Power, and all it works: dust, torches, repeaters, comparators, observers, lamps, pistons,\n" +
        "pressure plates and daylight detectors, and the doors, note blocks, dispensers and hoppers it moves.\n" +
        "Off, levers and buttons still flip, but power goes nowhere.",
    "features.liquids" to "Water and lava flowing, what they make where they meet, and concrete powder hardening in water.\n" +
        "Off, they stay as they're put.",
    "features.fallingBlocks" to "Sand, gravel, anvils and the like falling with nothing under them.\n" +
        "Off, they stay where they are, and hanging dripstone and snow break instead.",
    "features.growth" to "Random ticks, bone meal and trampling farmland: whatever grows, spreads, dries, decays, melts or weathers.",
    "features.machines" to "Furnaces, brewing stands, hoppers, dispensers, droppers, crafters and beacons working.\n" +
        "Off, they keep what's put in them, and do nothing with it.",
)

/**
 * These settings, with Kore's own from [file], kore.json. With no file,
 * writes one with every setting as these have it, each explained. A file
 * without some of them, or without some of a setting's own, as from an
 * older Kore, is written again with them, as these have them, keeping the
 * rest.
 */
fun ServerConfig.withKoreSettings(file: Path): ServerConfig {
    val defaults = json.encodeToJsonElement(KoreSettings.serializer(), KoreSettings(advertisedAddresses, operatorDomain, shutdownMessage, features)).jsonObject
    if (!file.exists()) {
        file.writeText(written(defaults))
        log.info("Made {} with Kore's own settings", file.toAbsolutePath())
        return this
    }
    val saved = try {
        json.parseToJsonElement(file.readText()).jsonObject
    } catch (e: SerializationException) {
        throw IllegalStateException("$file isn't JSON Kore can read: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$file isn't a JSON object of settings: ${e.message}", e)
    }
    val missing = missingFrom(saved, defaults)
    // Kore's settings in their own order, then anything else the file has.
    val whole = filledIn(saved, defaults)
    if (missing.isNotEmpty()) {
        file.writeText(written(whole))
        log.info("Added {} to {}", missing.joinToString(), file)
    }
    val settings = try {
        json.decodeFromJsonElement(KoreSettings.serializer(), whole)
    } catch (e: SerializationException) {
        throw IllegalStateException("$file has a setting Kore can't read: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$file has a setting Kore can't read: ${e.message}", e)
    }
    return copy(
        advertisedAddresses = settings.advertisedAddresses, operatorDomain = settings.operatorDomain, shutdownMessage = settings.shutdownMessage,
        features = settings.features,
    )
}

/** The settings in [defaults] that [saved] doesn't have, and those within one it has, as `setting.own`. */
private fun missingFrom(saved: JsonObject, defaults: JsonObject, prefix: String = ""): List<String> = defaults.flatMap { (key, default) ->
    val there = saved[key]
    when {
        there == null -> listOf(prefix + key)
        default is JsonObject && there is JsonObject -> missingFrom(there, default, "$prefix$key.")
        else -> emptyList()
    }
}

/** [saved] with what it's missing of [defaults], in the defaults' order, and anything else it has after. */
private fun filledIn(saved: JsonObject, defaults: JsonObject): JsonObject = JsonObject(
    defaults.mapValues { (key, default) ->
        val there = saved[key]
        if (default is JsonObject && there is JsonObject) filledIn(there, default) else there ?: default
    } + saved.filterKeys { it !in defaults },
)

/** [settings] as kore.json has them: each on a line of its own, below what it's for, and those within one indented. */
private fun written(settings: JsonObject, prefix: String = "", indent: String = "    "): String =
    settings.entries.joinToString(",\n\n", prefix = "{\n", postfix = "\n${indent.dropLast(4)}}") { (key, value) ->
        val comment = COMMENTS[prefix + key]?.lines()?.joinToString("") { "$indent// $it\n" }.orEmpty()
        val shown = if (value is JsonObject) written(value, "$prefix$key.", "$indent    ") else Json.encodeToString(JsonElement.serializer(), value)
        "$comment$indent${JsonPrimitive(key)}: $shown"
    } + if (prefix.isEmpty()) "\n" else ""
