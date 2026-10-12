package org.koremc.command

import org.koremc.protocol.AvailableCommands
import org.koremc.protocol.CommandData
import org.koremc.protocol.CommandEnum
import org.koremc.protocol.CommandMessage
import org.koremc.protocol.CommandParameter
import org.koremc.protocol.CommandPermission
import org.koremc.protocol.SoftEnum

/** How much of the line a syntax error quotes either side of the piece that didn't fit. */
private const val SYNTAX_ERROR_CONTEXT = 10

/**
 * The commands there are, by name and by the other names they go by. It
 * runs command lines against them, and lists for each player the commands
 * they may run, besides those the client has its own of. Whoever runs a
 * command needs its permission, and a cheat needs cheats on, except for
 * the server's owner, at the console.
 */
class CommandMap(val commands: List<Command>, private val allowCheats: Boolean) {
    private val byName: Map<String, Command> = commands.flatMap { command -> (listOf(command.name) + command.aliases).map { it to command } }.toMap()

    /** Each permission's list of commands, made once. */
    private val available = HashMap<CommandPermission, AvailableCommands>()

    /** The commands someone with [permission] may run, by name. */
    fun runnableBy(permission: CommandPermission): List<Command> = commands.filter { mayRun(it, permission) }.sortedBy { it.name }

    /** The command called [name], or going by it, if someone with [permission] may run it. */
    fun find(name: String, permission: CommandPermission): Command? = byName[name.lowercase()]?.takeIf { it.permission <= permission }

    private fun mayRun(command: Command, permission: CommandPermission) =
        command.permission <= permission && (!command.cheat || allowCheats || permission >= CommandPermission.OWNER)

    /**
     * Runs [line], like `/gamemode creative` (the slash is optional), for
     * [source]. A command they may not run is as unknown to them as one
     * that doesn't exist. A line that fits none of the ways to write the
     * command is a syntax error, at the piece furthest along that didn't fit.
     */
    fun run(source: CommandSource, line: String): CommandResult {
        val text = line.trim().removePrefix("/")
        val reader = CommandReader(text)
        val name = reader.word()
        val command = byName[name.lowercase()]?.takeIf { it.permission <= source.permission }
            ?: return failed("commands.generic.unknown", name)
        if (!mayRun(command, source.permission)) return failed("commands.generic.disabled")

        var furthest: SyntaxError? = null
        for (overload in command.overloads) {
            val values = try {
                parse(overload, CommandReader(text, reader.cursor))
            } catch (e: SyntaxError) {
                if (furthest == null || e.start > furthest.start) furthest = e
                continue
            }
            val call = CommandCall(source, values)
            try {
                overload.run(call)
            } catch (e: CommandFailure) {
                call.messages += CommandMessage(e.key, success = false, e.params)
            }
            return CommandResult(call.messages, call.successes)
        }
        val error = checkNotNull(furthest) { "/${command.name} has no way to write it" }
        val before = text.substring(maxOf(0, error.start - SYNTAX_ERROR_CONTEXT), error.start)
        val after = text.substring(error.end, minOf(text.length, error.end + SYNTAX_ERROR_CONTEXT))
        return failed("commands.generic.syntax", before, text.substring(error.start, error.end), after)
    }

    /** The values of [overload]'s parameters, read from [reader], which must leave nothing over. Optional ones can stop early. */
    private fun parse(overload: Overload, reader: CommandReader): Map<String, Any> {
        val values = HashMap<String, Any>()
        for (param in overload.params) {
            if (param.optional && reader.atEnd) break
            values[param.name] = param.type.read(reader)
        }
        if (!reader.atEnd) reader.failAt(reader.cursor)
        return values
    }

    private fun failed(key: String, vararg params: String) = CommandResult(listOf(CommandMessage(key, success = false, params.toList())), successes = 0)

    /**
     * The commands someone with [permission] may run, as the client wants
     * them, besides those it has its own of. Each enum goes in once, under
     * its name, and each word once.
     */
    fun availableTo(permission: CommandPermission): AvailableCommands =
        available.getOrPut(permission) { describe(runnableBy(permission).filter { it.sentToClients }) }

    private fun describe(commands: List<Command>): AvailableCommands {
        val words = LinkedHashMap<String, Int>()
        val enums = LinkedHashMap<String, CommandEnum>()
        val softEnums = LinkedHashMap<String, SoftEnum>()
        fun enumIndex(name: String, values: List<String>): Int {
            enums.getOrPut(name) { CommandEnum(name, values.map { words.getOrPut(it) { words.size } }) }
            return enums.keys.indexOf(name)
        }
        fun softEnumIndex(name: String, values: List<String>): Int {
            softEnums.getOrPut(name) { SoftEnum(name, values) }
            return softEnums.keys.indexOf(name)
        }
        val described = commands.map { command ->
            // A command's other names go in an enum with its own, or the client stops showing the one it was given.
            val aliases = if (command.aliases.isEmpty()) -1 else enumIndex("${command.name.replaceFirstChar(Char::uppercaseChar)}Aliases", listOf(command.name) + command.aliases)
            val overloads = command.overloads.map { overload ->
                overload.params.map { param ->
                    when (val type = param.type) {
                        is EnumType -> CommandParameter(param.name, CommandParameter.VALID or CommandParameter.ENUM or enumIndex(type.name, type.values), param.optional)
                        is SoftEnumType -> CommandParameter(param.name, CommandParameter.VALID or CommandParameter.SOFT_ENUM or softEnumIndex(type.name, type.values), param.optional)
                        is BasicType -> CommandParameter(param.name, CommandParameter.VALID or type.symbol, param.optional)
                    }
                }
            }
            CommandData(command.name, command.description, command.permission, aliases, overloads)
        }
        return AvailableCommands(words.keys.toList(), enums.values.toList(), described, softEnums.values.toList())
    }
}
