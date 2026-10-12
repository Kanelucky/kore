package org.koremc.command

import org.koremc.world.BlockPos
import org.koremc.world.Vec3
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A piece of a command line that doesn't fit the command, from [start] up
 * to [end]: the game reports it as a syntax error, quoting the line
 * around it.
 */
class SyntaxError(val start: Int, val end: Int) : Exception(null, null, false, false)

/**
 * Reads a command line a piece at a time, for each parameter of a way to
 * write a command to take the piece it expects. A piece that doesn't fit
 * is a [SyntaxError]. Spaces separate pieces, and quotes keep a name or a
 * word with spaces in it together.
 */
class CommandReader(val line: String, var cursor: Int = 0) {
    /** Whether only spaces are left. */
    val atEnd: Boolean get() = skipSpaces().cursor >= line.length

    fun skipSpaces() = apply { while (cursor < line.length && line[cursor].isWhitespace()) cursor++ }

    fun peek(): Char? = skipSpaces().line.getOrNull(cursor)

    /** Everything up to the next space, or the end. */
    fun word(): String {
        val start = skipSpaces().cursor
        while (cursor < line.length && !line[cursor].isWhitespace()) cursor++
        return line.substring(start, cursor)
    }

    /** A word, or text in quotes, where a backslash keeps a quote or another backslash as it is. */
    fun string(): String {
        if (peek() != '"') return word()
        val start = cursor++
        val text = StringBuilder()
        while (cursor < line.length) {
            val char = line[cursor++]
            when {
                char == '"' -> return text.toString()
                char == '\\' && cursor < line.length -> text.append(line[cursor++])
                else -> text.append(char)
            }
        }
        throw SyntaxError(start, cursor)
    }

    /** Everything left, without the spaces before it. */
    fun rest(): String = skipSpaces().line.substring(cursor).also { cursor = line.length }

    /** A syntax error at the piece starting at [start], which runs to the next space. */
    fun failAt(start: Int): Nothing {
        var end = start
        while (end < line.length && !line[end].isWhitespace()) end++
        throw SyntaxError(start, end)
    }
}

/**
 * What a parameter takes. Each is one of the client's own types, which
 * [symbol] numbers as its command grammar does, or an [EnumType].
 */
sealed class ParamType(
    /** How `/help` shows the type, like `int`. */
    open val usage: String,
) {
    /** Reads the parameter's value from [reader], or throws a [SyntaxError]. */
    abstract fun read(reader: CommandReader): Any
}

/** One of the client's built-in types. */
sealed class BasicType(val symbol: Int, usage: String) : ParamType(usage)

object IntType : BasicType(0x01, "int") {
    override fun read(reader: CommandReader): Int {
        val start = reader.skipSpaces().cursor
        return reader.word().toIntOrNull() ?: reader.failAt(start)
    }
}

object FloatType : BasicType(0x03, "float") {
    override fun read(reader: CommandReader): Double {
        val start = reader.skipSpaces().cursor
        return reader.word().toDoubleOrNull()?.takeIf { it.isFinite() } ?: reader.failAt(start)
    }
}

/** A number, or one relative to the current value with a `~`, like a rotation. */
object RelativeFloatType : BasicType(0x04, "value") {
    override fun read(reader: CommandReader): Relative {
        val start = reader.skipSpaces().cursor
        val word = reader.word()
        val relative = word.startsWith('~')
        val number = word.removePrefix("~")
        val value = if (relative && number.isEmpty()) 0.0 else number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: reader.failAt(start)
        return Relative(value, relative)
    }
}

/** A player, by name or by a selector like `@a`. */
object TargetType : BasicType(0x08, "target") {
    override fun read(reader: CommandReader): Target = readTarget(reader)
}

/** A word, or text in quotes. */
object StringType : BasicType(0x38, "string") {
    override fun read(reader: CommandReader): String {
        val start = reader.skipSpaces().cursor
        return reader.string().ifEmpty { reader.failAt(start) }
    }
}

/** A block, by three coordinates. */
object BlockPositionType : BasicType(0x40, "x y z") {
    override fun read(reader: CommandReader) = Coordinates.read(reader)
}

/** A point, by three coordinates. */
object PositionType : BasicType(0x41, "x y z") {
    override fun read(reader: CommandReader) = Coordinates.read(reader)
}

/** The rest of the line, which may be nothing. */
object MessageType : BasicType(0x44, "message") {
    override fun read(reader: CommandReader): String = reader.rest()
}

/**
 * One of a fixed set of words, like a game mode, which the client offers
 * as it completes the command. Words are told apart without regard to case,
 * and read as the set has them.
 */
class EnumType(val name: String, val values: List<String>) : ParamType(name) {
    private val byLowercase = values.associateBy { it.lowercase() }

    /** An enum of one word is shown as the word, as it can't be anything else. */
    override val usage get() = values.singleOrNull() ?: name

    override fun read(reader: CommandReader): String {
        val start = reader.skipSpaces().cursor
        return byLowercase[reader.word().lowercase()] ?: reader.failAt(start)
    }
}

/**
 * One of a set of words, which goes to the client as a soft enum. The
 * client keeps that apart from its own lists, where it would add to an
 * [EnumType] of the same name: its own item list, for one, has every item's
 * name with and without its namespace, and the old names the game still
 * knows, like `totem`. Words can come with [namespace], as in
 * `minecraft:stone`, and are read without it.
 */
class SoftEnumType(val name: String, val values: List<String>, private val namespace: String? = null) : ParamType(name) {
    private val byLowercase = values.associateBy { it.lowercase() }

    override fun read(reader: CommandReader): String {
        val start = reader.skipSpaces().cursor
        val word = reader.word().lowercase()
        return byLowercase[namespace?.let { word.removePrefix("$it:") } ?: word] ?: reader.failAt(start)
    }
}

/** A number as a command gave it: as it is, or as an offset from a value it's [relative] to. */
data class Relative(val value: Double, val relative: Boolean) {
    fun from(current: Double) = if (relative) current + value else value
}

/**
 * A coordinate as a command gives it: a number, an offset from where the
 * command runs (`~`), or along the way it faces (`^`). A whole number on
 * its own is [whole], which a point centres in the block.
 */
data class Coordinate(val value: Double, val kind: Kind, val whole: Boolean = false) {
    enum class Kind { ABSOLUTE, RELATIVE, LOCAL }
}

/**
 * Three coordinates, for a block or a point. Offsets with `~` and `^` can
 * run together without spaces, like `~~1~`. Those along the way the
 * command faces, `^left ^up ^forwards`, can't be mixed with the others.
 */
data class Coordinates(val x: Coordinate, val y: Coordinate, val z: Coordinate) {
    /**
     * The point these give from [origin], where the command runs, facing
     * the way [yaw] and [pitch] say. Whole numbers across, for x and z, are
     * the middle of the block, as a player standing there would be.
     */
    fun point(origin: Vec3, yaw: Float, pitch: Float): Vec3 {
        if (x.kind == Coordinate.Kind.LOCAL) return local(origin, yaw, pitch)
        fun resolve(coordinate: Coordinate, from: Float, centre: Boolean): Float = when {
            coordinate.kind == Coordinate.Kind.RELATIVE -> (from + coordinate.value).toFloat()
            centre && coordinate.whole -> (coordinate.value + 0.5).toFloat()
            else -> coordinate.value.toFloat()
        }
        return Vec3(resolve(x, origin.x, centre = true), resolve(y, origin.y, centre = false), resolve(z, origin.z, centre = true))
    }

    /** The block these give from [origin], facing the way [yaw] and [pitch] say. */
    fun block(origin: Vec3, yaw: Float, pitch: Float): BlockPos {
        if (x.kind == Coordinate.Kind.LOCAL) return local(origin, yaw, pitch).toBlockPos()
        fun resolve(coordinate: Coordinate, from: Float) =
            floor(if (coordinate.kind == Coordinate.Kind.RELATIVE) from + coordinate.value else coordinate.value).toInt()
        return BlockPos(resolve(x, origin.x), resolve(y, origin.y), resolve(z, origin.z))
    }

    /** The point `^x ^y ^z` gives: to the left, up and forwards of [origin], facing the way [yaw] and [pitch] say. */
    private fun local(origin: Vec3, yaw: Float, pitch: Float): Vec3 {
        val yawRadians = Math.toRadians(yaw + 90.0)
        val pitchRadians = Math.toRadians(-pitch.toDouble())
        val upRadians = Math.toRadians(-pitch + 90.0)
        val forwards = doubleArrayOf(cos(yawRadians) * cos(pitchRadians), sin(pitchRadians), sin(yawRadians) * cos(pitchRadians))
        val up = doubleArrayOf(cos(yawRadians) * cos(upRadians), sin(upRadians), sin(yawRadians) * cos(upRadians))
        // Left is across both: up crossed with forwards.
        val left = doubleArrayOf(
            up[1] * forwards[2] - up[2] * forwards[1],
            up[2] * forwards[0] - up[0] * forwards[2],
            up[0] * forwards[1] - up[1] * forwards[0],
        )
        fun along(axis: Int) = left[axis] * x.value + up[axis] * y.value + forwards[axis] * z.value
        return Vec3((origin.x + along(0)).toFloat(), (origin.y + along(1)).toFloat(), (origin.z + along(2)).toFloat())
    }

    companion object {
        fun read(reader: CommandReader): Coordinates {
            val start = reader.skipSpaces().cursor
            val x = readCoordinate(reader)
            val y = readCoordinate(reader)
            val z = readCoordinate(reader)
            val local = listOf(x, y, z).map { it.kind == Coordinate.Kind.LOCAL }
            if (local.distinct().size > 1) reader.failAt(start)
            return Coordinates(x, y, z)
        }

        /** A coordinate, which ends at a space, or where the next one starts with `~` or `^`. */
        private fun readCoordinate(reader: CommandReader): Coordinate {
            val start = reader.skipSpaces().cursor
            val line = reader.line
            val kind = when (line.getOrNull(start)) {
                '~' -> Coordinate.Kind.RELATIVE
                '^' -> Coordinate.Kind.LOCAL
                null -> reader.failAt(start)
                else -> Coordinate.Kind.ABSOLUTE
            }
            if (kind != Coordinate.Kind.ABSOLUTE) reader.cursor++
            val numberStart = reader.cursor
            while (reader.cursor < line.length && !line[reader.cursor].isWhitespace() && line[reader.cursor] !in "~^") reader.cursor++
            val number = line.substring(numberStart, reader.cursor)
            if (number.isEmpty() && kind != Coordinate.Kind.ABSOLUTE) return Coordinate(0.0, kind)
            val value = number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: reader.failAt(start)
            return Coordinate(value, kind, whole = kind == Coordinate.Kind.ABSOLUTE && '.' !in number)
        }
    }
}
