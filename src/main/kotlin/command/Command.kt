package org.koremc.command

import org.koremc.protocol.CommandMessage
import org.koremc.protocol.CommandPermission
import org.koremc.world.BlockPos
import org.koremc.world.Vec3

/** A parameter of a way to write a command: its name, as the client shows it, what it takes, and whether it can be left out. */
data class Param(val name: String, val type: ParamType, val optional: Boolean = false)

/** One way to write a command, and what the command does written that way. */
class Overload(val params: List<Param>, val run: CommandCall.() -> Unit)

/**
 * A command players and the console can run: its [name], and any others
 * it goes by, the key of its description in the client's language, who
 * may run it, and whether it's a cheat, which a world without cheats
 * refuses to players. The game tries each way to write it in turn, and
 * runs the first the command line fits.
 */
class Command(
    val name: String,
    val permission: CommandPermission,
    val cheat: Boolean,
    val overloads: List<Overload>,
    val aliases: List<String> = emptyList(),
    val description: String = "commands.$name.description",
    /** Whether players' clients hear of it. One the client has its own of, like `/help`, is left to the client. */
    val sentToClients: Boolean = true,
) {
    /** How `/help` shows each way to write it, like `/gamemode <gameMode: GameMode> [player: target]`, or `/xp <amount: int>L`. */
    val usages: List<String>
        get() = overloads.map { overload ->
            (listOf("/$name") + overload.params.map { param ->
                val inside = if (param.type is EnumType && param.type.values.size == 1) param.type.usage else "${param.name}: ${param.type.usage}"
                val suffix = (param.type as? SuffixedIntType)?.suffix.orEmpty()
                if (param.optional) "[$inside]$suffix" else "<$inside>$suffix"
            }).joinToString(" ")
        }
}

/** Builds a [Command] from its ways to write it. */
class CommandBuilder {
    internal val overloads = mutableListOf<Overload>()

    fun overload(vararg params: Param, run: CommandCall.() -> Unit) {
        overloads += Overload(params.toList(), run)
    }
}

fun command(
    name: String,
    permission: CommandPermission,
    cheat: Boolean,
    aliases: List<String> = emptyList(),
    description: String = "commands.$name.description",
    sentToClients: Boolean = true,
    build: CommandBuilder.() -> Unit,
) = Command(name, permission, cheat, CommandBuilder().apply(build).overloads, aliases, description, sentToClients)

fun required(name: String, type: ParamType) = Param(name, type)

fun optional(name: String, type: ParamType) = Param(name, type, optional = true)

/**
 * Whatever runs a command: a player, or the server console. Coordinates
 * with `~` and `^` start from where it is and the way it faces.
 */
interface CommandSource {
    /** Its name, as messages about what it did show it. */
    val name: String
    val permission: CommandPermission

    /** Where it is: a player's feet, or for the console, the world's spawn. */
    val origin: Vec3
    val yaw: Float
    val pitch: Float
}

/** A command failing, with the key of the message that says why and the message's parameters. */
class CommandFailure(val key: String, val params: List<String>) : Exception(key, null, false, false)

/**
 * A run of a command: who ran it, the values its parameters took, and the
 * messages it has to show for it. A step that fails ends the run with
 * [fail]; one that only partly works says so with [error] and goes on.
 */
class CommandCall(val source: CommandSource, private val values: Map<String, Any>) {
    val messages = mutableListOf<CommandMessage>()

    /** How many times the command did what it does, as its output counts them. */
    var successes = 0

    /** Whether the parameter called [name] was given. */
    fun has(name: String) = name in values

    fun int(name: String): Int = values.getValue(name) as Int

    fun intOr(name: String, default: Int): Int = values[name] as Int? ?: default

    fun string(name: String): String = values.getValue(name) as String

    fun stringOrNull(name: String): String? = values[name] as String?

    fun relative(name: String): Relative? = values[name] as Relative?

    fun target(name: String): Target = values.getValue(name) as Target

    fun targetOrNull(name: String): Target? = values[name] as Target?

    /** The point the coordinates called [name] give from where the command runs. */
    fun point(name: String): Vec3 = (values.getValue(name) as Coordinates).point(source.origin, source.yaw, source.pitch)

    /** The block the coordinates called [name] give from where the command runs. */
    fun block(name: String): BlockPos = (values.getValue(name) as Coordinates).block(source.origin, source.yaw, source.pitch)

    /** Adds a message that the command worked, and counts it. */
    fun success(key: String, vararg params: Any) {
        messages += CommandMessage(key, success = true, params.map(Any::toString))
        successes++
    }

    /** Adds a message that part of the command didn't work, and goes on with the rest. */
    fun error(key: String, vararg params: Any) {
        messages += CommandMessage(key, success = false, params.map(Any::toString))
    }

    /** Ends the command, failed, with a message that says why. */
    fun fail(key: String, vararg params: Any): Nothing = throw CommandFailure(key, params.map(Any::toString))
}

/** What running a command line came to: the messages to show for it, and how many times it worked. */
data class CommandResult(val messages: List<CommandMessage>, val successes: Int)
