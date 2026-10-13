package org.koremc.command

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    explicitNulls = false
}

/** An entry in `permissions.json`: who a player is, and their permission level, written in that order. */
@Serializable
private data class PermissionEntry(val name: String? = null, val permission: String, val xuid: String? = null)

private const val OPERATOR = "operator"

/**
 * The server's operators, who may run the commands players may not, kept
 * in [file] as the game's dedicated server keeps them in its
 * `permissions.json`: a list of entries naming each player's permission
 * level and XUID. Kore also writes the name the player had, first, which
 * is how it knows a player without an XUID, who isn't signed in to Xbox
 * Live, as only an offline server lets in. Other entries in the file are
 * kept as they are. Without a file, the operators last only as long as the server.
 */
class Operators(private val file: Path? = null) {
    private val entries: MutableList<PermissionEntry> = file?.takeIf { it.exists() }?.let(::read)?.toMutableList() ?: mutableListOf()

    /** Whether the player called [name], with [xuid] if they have one, is an operator. */
    fun contains(name: String, xuid: String?): Boolean = entries.any { it.permission == OPERATOR && it.names(name, xuid) }

    /** Makes the player called [name] an operator, unless they already are. Returns whether they weren't. */
    fun add(name: String, xuid: String?): Boolean {
        if (contains(name, xuid)) return false
        entries.removeIf { it.names(name, xuid) }
        entries += PermissionEntry(name, OPERATOR, xuid)
        save()
        return true
    }

    /** Makes the player called [name] an operator no longer. Returns whether they were. */
    fun remove(name: String, xuid: String?): Boolean {
        if (!entries.removeIf { it.permission == OPERATOR && it.names(name, xuid) }) return false
        save()
        return true
    }

    /** Whether this entry is the player called [name], by their XUID when both have one, or else by name. */
    private fun PermissionEntry.names(name: String, xuid: String?) =
        if (this.xuid != null && xuid != null) this.xuid == xuid else this.xuid == null && this.name.equals(name, ignoreCase = true)

    private fun save() {
        file?.writeText(json.encodeToString(entries.toList()))
    }

    private companion object {
        fun read(file: Path): List<PermissionEntry> = try {
            json.decodeFromString(file.readText())
        } catch (e: SerializationException) {
            throw IllegalStateException("$file isn't a list of permissions Kore can read: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$file isn't a list of permissions Kore can read: ${e.message}", e)
        }
    }
}
