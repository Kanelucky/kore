package org.koremc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
)

/** What each setting is for, written above it. */
private val COMMENTS = mapOf(
    "advertisedAddresses" to "The addresses players reach the server at: IP addresses or host names.\n" +
        "Left empty, every address this computer has that others can reach.",
    "operatorDomain" to "Shown to players, as text the server can't vouch for, the first time they accept its key.",
    "shutdownMessage" to "What players still on the server are told as it stops.",
)

/**
 * These settings, with Kore's own from [file], kore.json. With no file,
 * writes one with every setting as these have it, each explained. A file
 * without some of them, as from an older Kore, is written again with
 * them, as these have them, keeping the rest.
 */
fun ServerConfig.withKoreSettings(file: Path): ServerConfig {
    val defaults = json.encodeToJsonElement(KoreSettings.serializer(), KoreSettings(advertisedAddresses, operatorDomain, shutdownMessage)).jsonObject
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
    val missing = defaults.keys - saved.keys
    // Kore's settings in their own order, then anything else the file has.
    val whole = JsonObject(defaults.mapValues { (key, default) -> saved[key] ?: default } + saved)
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
    return copy(advertisedAddresses = settings.advertisedAddresses, operatorDomain = settings.operatorDomain, shutdownMessage = settings.shutdownMessage)
}

/** [settings] as kore.json has them: each on a line of its own, below what it's for. */
private fun written(settings: JsonObject) = settings.entries.joinToString(",\n\n", prefix = "{\n", postfix = "\n}\n") { (key, value) ->
    val comment = COMMENTS[key]?.lines()?.joinToString("") { "    // $it\n" }.orEmpty()
    "$comment    ${JsonPrimitive(key)}: ${Json.encodeToString(JsonElement.serializer(), value)}"
}
