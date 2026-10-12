package org.koremc.command

import org.koremc.protocol.GameMode
import org.koremc.world.Vec3
import kotlin.random.Random

/** What selectors know of a player to pick them out by. */
interface Selectable {
    val name: String

    /** Where the player's feet are, which distances are measured to. */
    val feet: Vec3
    val yaw: Float
    val pitch: Float
    val gameMode: GameMode
}

/** Who a command means: a player by name, or those a selector like `@a[r=10]` picks out. */
sealed interface Target {
    data class Name(val name: String) : Target

    data class Selector(val kind: Char, val filters: Filters = Filters()) : Target

    /**
     * The players among [players] this means, for a command run by [source]
     * from [origin], facing the way [yaw] and [pitch] say. Only a player can
     * be `@s`, so from anywhere else, it means nobody.
     */
    fun <T : Selectable> select(players: List<T>, source: T?, origin: Vec3, yaw: Float, pitch: Float, random: Random = Random): List<T> = when (this) {
        is Name -> players.filter { it.name.equals(name, ignoreCase = true) }
        is Selector -> {
            val from = filters.origin(origin, yaw, pitch)
            val matching = (if (kind == 's') listOfNotNull(source) else players).filter { filters.match(it, from) }
            val count = filters.count
            when (kind) {
                'p', 'n' -> byDistance(matching, from, count ?: 1)
                'r' -> matching.shuffled(random).take(count ?: 1)
                else -> if (count == null) matching else byDistance(matching, from, count)
            }
        }
    }

    private fun <T : Selectable> byDistance(players: List<T>, from: Vec3, count: Int): List<T> {
        // A negative count takes the farthest.
        val sorted = players.sortedBy { distanceSquared(it.feet, from) }
        return if (count < 0) sorted.reversed().take(-count) else sorted.take(count)
    }
}

/** A filter that matches what it names, or with `!`, everything else. */
data class Match<T>(val value: T, val negated: Boolean) {
    fun test(matches: (T) -> Boolean) = matches(value) != negated
}

/**
 * The arguments that narrow down what a selector picks: where to measure
 * from (`x`, `y`, `z`), within what distance (`r`, `rm`) or box (`dx`,
 * `dy`, `dz`), how many (`c`), and by name, game mode, type, tag, family,
 * experience level and rotation. Players are the only entities a selector
 * picks, and they have no tags and no experience yet.
 */
data class Filters(
    val x: Coordinate? = null,
    val y: Coordinate? = null,
    val z: Coordinate? = null,
    val radius: Double? = null,
    val minRadius: Double? = null,
    val dx: Double? = null,
    val dy: Double? = null,
    val dz: Double? = null,
    val count: Int? = null,
    val names: List<Match<String>> = emptyList(),
    val gameModes: List<Match<GameMode>> = emptyList(),
    val types: List<Match<String>> = emptyList(),
    val tags: List<Match<String>> = emptyList(),
    val families: List<Match<String>> = emptyList(),
    val maxLevel: Int? = null,
    val minLevel: Int? = null,
    val maxPitch: Double? = null,
    val minPitch: Double? = null,
    val maxYaw: Double? = null,
    val minYaw: Double? = null,
) {
    /** Where to measure from: [origin], unless `x`, `y` or `z` say otherwise. */
    fun origin(origin: Vec3, yaw: Float, pitch: Float): Vec3 {
        fun resolve(coordinate: Coordinate?, from: Float) =
            if (coordinate == null || coordinate.kind == Coordinate.Kind.RELATIVE) from + (coordinate?.value ?: 0.0).toFloat() else coordinate.value.toFloat()
        return Vec3(resolve(x, origin.x), resolve(y, origin.y), resolve(z, origin.z))
    }

    fun match(player: Selectable, from: Vec3): Boolean {
        val distance = distanceSquared(player.feet, from)
        if (radius != null && distance > radius * radius) return false
        if (minRadius != null && distance < minRadius * minRadius) return false
        if ((dx != null || dy != null || dz != null) && !inBox(player.feet, from)) return false
        if (!names.all { match -> match.test { it.equals(player.name, ignoreCase = true) } }) return false
        if (!gameModes.all { match -> match.test { it == player.gameMode } }) return false
        if (!types.all { match -> match.test { it.removePrefix("minecraft:") == "player" } }) return false
        // An empty tag means having no tags, which every player has.
        if (!tags.all { match -> match.test { it.isEmpty() } }) return false
        if (!families.all { match -> match.test { it == "player" } }) return false
        if (minLevel != null && minLevel > 0 || maxLevel != null && maxLevel < 0) return false
        if (minPitch != null && player.pitch < minPitch || maxPitch != null && player.pitch > maxPitch) return false
        if (minYaw != null || maxYaw != null) {
            val yaw = wrapDegrees(player.yaw.toDouble())
            val min = wrapDegrees(minYaw ?: -180.0)
            val max = wrapDegrees(maxYaw ?: 180.0)
            // A range across the back, where the angle wraps, takes either end.
            val within = if (min <= max) yaw in min..max else yaw >= min || yaw <= max
            if (!within) return false
        }
        return true
    }

    /** Whether a player standing at [feet] reaches into the box from [from] across `dx`, `dy` and `dz` blocks, which takes in the block at its far corner. */
    private fun inBox(feet: Vec3, from: Vec3): Boolean {
        fun span(start: Float, size: Double?): ClosedFloatingPointRange<Double> {
            val length = size ?: 0.0
            return if (length >= 0) start.toDouble()..start + length + 1 else start + length..start + 1.0
        }
        val half = PLAYER_HALF_WIDTH
        val xs = span(from.x, dx)
        val ys = span(from.y, dy)
        val zs = span(from.z, dz)
        return feet.x + half >= xs.start && feet.x - half <= xs.endInclusive &&
            feet.y + PLAYER_HEIGHT >= ys.start && feet.y <= ys.endInclusive &&
            feet.z + half >= zs.start && feet.z - half <= zs.endInclusive
    }
}

private const val PLAYER_HALF_WIDTH = 0.3
private const val PLAYER_HEIGHT = 1.8

private fun distanceSquared(a: Vec3, b: Vec3): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    val dz = (a.z - b.z).toDouble()
    return dx * dx + dy * dy + dz * dz
}

/** [degrees] brought between -180 and 180. */
private fun wrapDegrees(degrees: Double): Double {
    val wrapped = degrees % 360
    return when {
        wrapped >= 180 -> wrapped - 360
        wrapped < -180 -> wrapped + 360
        else -> wrapped
    }
}

/** The kinds of selector: the player running the command, the nearest player, all players, a random one, all entities and the nearest entity. */
private const val SELECTOR_KINDS = "sparen"

/** A player's name, or a selector: `@` and its kind, then any arguments in square brackets. */
internal fun readTarget(reader: CommandReader): Target {
    val start = reader.skipSpaces().cursor
    if (reader.peek() != '@') return Target.Name(reader.string().ifEmpty { reader.failAt(start) })
    val line = reader.line
    val kind = line.getOrNull(start + 1)?.takeIf { it in SELECTOR_KINDS } ?: reader.failAt(start)
    reader.cursor = start + 2
    if (line.getOrNull(reader.cursor) != '[') {
        // A selector ends where its kind does.
        if (reader.cursor < line.length && !line[reader.cursor].isWhitespace()) reader.failAt(start)
        return Target.Selector(kind)
    }
    val arguments = readArguments(reader) ?: reader.failAt(start)
    return Target.Selector(kind, filtersOf(arguments) ?: reader.failAt(start))
}

/** The `key=value` pairs between square brackets, with the reader past them, or null if they don't close. */
private fun readArguments(reader: CommandReader): List<Pair<String, String>>? {
    val line = reader.line
    var cursor = reader.cursor + 1
    val arguments = mutableListOf<Pair<String, String>>()
    val piece = StringBuilder()
    var quoted = false
    fun finish(): Boolean {
        val text = piece.toString()
        piece.clear()
        if (text.isBlank()) return arguments.isEmpty()
        val equals = text.indexOf('=').takeIf { it > 0 } ?: return false
        arguments += text.substring(0, equals).trim().lowercase() to text.substring(equals + 1).trim()
        return true
    }
    while (cursor < line.length) {
        val char = line[cursor++]
        when {
            char == '"' -> quoted = !quoted
            quoted -> piece.append(char)
            char == ',' -> if (!finish()) return null
            char == ']' -> {
                if (!finish()) return null
                reader.cursor = cursor
                return arguments
            }
            else -> piece.append(char)
        }
    }
    return null
}

/** What [arguments] filter by, or null if one isn't an argument Kore knows, or its value doesn't fit. */
private fun filtersOf(arguments: List<Pair<String, String>>): Filters? {
    var filters = Filters()
    for ((key, value) in arguments) {
        val negated = value.startsWith('!')
        val plain = value.removePrefix("!").trim()
        fun number() = value.toDoubleOrNull()?.takeIf { it.isFinite() }
        filters = when (key) {
            "x" -> filters.copy(x = coordinateOf(value) ?: return null)
            "y" -> filters.copy(y = coordinateOf(value) ?: return null)
            "z" -> filters.copy(z = coordinateOf(value) ?: return null)
            "r" -> filters.copy(radius = number()?.takeIf { it >= 0 } ?: return null)
            "rm" -> filters.copy(minRadius = number()?.takeIf { it >= 0 } ?: return null)
            "dx" -> filters.copy(dx = number() ?: return null)
            "dy" -> filters.copy(dy = number() ?: return null)
            "dz" -> filters.copy(dz = number() ?: return null)
            "c" -> filters.copy(count = value.toIntOrNull()?.takeIf { it != 0 } ?: return null)
            "name" -> filters.copy(names = filters.names + Match(plain, negated))
            "m" -> filters.copy(gameModes = filters.gameModes + Match(gameModeNamed(plain) ?: return null, negated))
            "type" -> filters.copy(types = filters.types + Match(plain, negated))
            "tag" -> filters.copy(tags = filters.tags + Match(plain, negated))
            "family" -> filters.copy(families = filters.families + Match(plain, negated))
            "l" -> filters.copy(maxLevel = value.toIntOrNull() ?: return null)
            "lm" -> filters.copy(minLevel = value.toIntOrNull() ?: return null)
            "rx" -> filters.copy(maxPitch = number() ?: return null)
            "rxm" -> filters.copy(minPitch = number() ?: return null)
            "ry" -> filters.copy(maxYaw = number() ?: return null)
            "rym" -> filters.copy(minYaw = number() ?: return null)
            else -> return null
        }
    }
    return filters
}

/** A selector's coordinate: a number, or an offset from where the command runs with `~`. */
private fun coordinateOf(value: String): Coordinate? {
    if (!value.startsWith('~')) return value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Coordinate(it, Coordinate.Kind.ABSOLUTE) }
    val offset = value.removePrefix("~")
    return Coordinate(if (offset.isEmpty()) 0.0 else offset.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null, Coordinate.Kind.RELATIVE)
}

/** The game mode [name] stands for, in any of the ways commands write them: `creative`, `c` or `1`. */
fun gameModeNamed(name: String): GameMode? = when (name.lowercase()) {
    "survival", "s", "0" -> GameMode.SURVIVAL
    "creative", "c", "1" -> GameMode.CREATIVE
    "adventure", "a", "2" -> GameMode.ADVENTURE
    "default", "d", "5" -> GameMode.DEFAULT
    "spectator", "6" -> GameMode.SPECTATOR
    else -> null
}
