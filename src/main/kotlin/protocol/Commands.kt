package org.koremc.protocol

import io.netty.buffer.ByteBuf
import java.util.UUID

private const val SET_PLAYER_GAME_TYPE = 62
private const val AVAILABLE_COMMANDS = 76
internal const val COMMAND_REQUEST = 77
private const val COMMAND_OUTPUT = 79
private const val UPDATE_PLAYER_GAME_TYPE = 151

/**
 * How much a player, or whatever else runs commands, may do with them,
 * from anyone's commands up to the server console's. Declared in order:
 * abilities send each as its ordinal, and commands by [wireName].
 */
enum class CommandPermission(val wireName: String) {
    /** Every player's, for commands like `/tell`. */
    ANY("any"),

    /** Command blocks'. */
    GAME_DIRECTORS("gamedirectors"),

    /** Operators'. */
    ADMIN("admin"),

    /** The host's, in a world a player hosts. */
    HOST("host"),

    /** The server console's. */
    OWNER("owner"),
    INTERNAL("internal"),
}

/**
 * Where a command came from, as its request says and its output repeats,
 * so the client can pair the two: the kind of origin, by name, and the
 * UUID the client gave that run of the command. The IDs are for automation,
 * like a websocket server, and empty for a player.
 */
data class CommandOrigin(val type: String, val uuid: UUID, val requestId: String = "", val playerId: Long = 0) {
    companion object {
        const val PLAYER = "player"
    }
}

/** A command the player typed, like `/gamemode creative`, slash and all. */
data class CommandRequest(val command: String, val origin: CommandOrigin, val internal: Boolean, val version: String) : ClientPacket

internal fun decodeCommandRequest(buffer: ByteBuf): CommandRequest {
    val command = buffer.readString()
    val origin = buffer.readCommandOrigin()
    val internal = buffer.readBoolean()
    return CommandRequest(command, origin, internal, version = buffer.readString())
}

private fun ByteBuf.readCommandOrigin() = CommandOrigin(type = readString(), uuid = readUuid(), requestId = readString(), playerId = readLongLE())

private fun ByteBuf.writeCommandOrigin(origin: CommandOrigin) {
    writeString(origin.type)
    writeUuid(origin.uuid)
    writeString(origin.requestId)
    writeLongLE(origin.playerId)
}

/**
 * A line of a command's output: the key of a message in the client's own
 * language, filled in with [parameters], or the text itself. The client
 * shows those that report a failure in red.
 */
data class CommandMessage(val message: String, val success: Boolean, val parameters: List<String> = emptyList())

/** What a command the client asked for did, for the client to show in the chat, under the request's [origin]. */
data class CommandOutput(val origin: CommandOrigin, val messages: List<CommandMessage>, val successCount: Int) : ServerPacket {
    override val id get() = COMMAND_OUTPUT

    override fun encodeBody(out: ByteBuf) {
        out.writeCommandOrigin(origin)
        out.writeString(ALL_OUTPUT)
        out.writeIntLE(successCount)
        out.writeVarUInt(messages.size)
        for (message in messages) {
            out.writeString(message.message)
            out.writeBoolean(message.success)
            out.writeVarUInt(message.parameters.size)
            message.parameters.forEach(out::writeString)
        }
        out.writeBoolean(false) // A data set, which only automation asks for
    }

    private companion object {
        /** Show every message, rather than the last or none. */
        const val ALL_OUTPUT = "alloutput"
    }
}

/**
 * A command as `AvailableCommands` describes it: its [name], the key of
 * its [description] in the client's language, who may run it, the enum of
 * names it also goes by, if any, and each way to write it.
 */
data class CommandData(
    val name: String,
    val description: String,
    val permission: CommandPermission,
    /** The index of the enum holding the command's names, its own among them, or -1 for a command with no other. */
    val aliases: Int,
    val overloads: List<List<CommandParameter>>,
)

/**
 * A parameter of one way to write a command. Its [symbol] says what it
 * takes: one of the client's built-in types, or an enum, by index.
 */
data class CommandParameter(val name: String, val symbol: Int, val optional: Boolean, val options: Int = 0) {
    companion object {
        /** Set on every symbol, for a type the client should check. */
        const val VALID = 0x100000

        /** Set on a symbol whose low bits index an enum rather than name a type. */
        const val ENUM = 0x200000

        /** Set on a symbol whose low bits index a soft enum. */
        const val SOFT_ENUM = 0x4000000

    }
}

/** A set of words a parameter may take, as indices into the packet's [AvailableCommands.enumValues]. */
data class CommandEnum(val name: String, val values: List<Int>)

/**
 * A set of words a parameter may take, written out. Unlike a [CommandEnum],
 * the client keeps it apart from its own lists of the same name, and the
 * server can change it without sending every command again.
 */
data class SoftEnum(val name: String, val values: List<String>)

/**
 * The commands a player may run, which the client completes as they type
 * and lists in its own help. Every enum value is written once, and enums
 * point at them by index. It replaces any list the client had, so changing
 * what a player may run sends the whole list again.
 */
class AvailableCommands(
    val enumValues: List<String>,
    val enums: List<CommandEnum>,
    val commands: List<CommandData>,
    val softEnums: List<SoftEnum> = emptyList(),
) : ServerPacket {
    override val id get() = AVAILABLE_COMMANDS

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(enumValues.size)
        enumValues.forEach(out::writeString)
        out.writeVarUInt(0) // Chained subcommand values, which only /execute has
        out.writeVarUInt(0) // Suffixes, like the L of /xp 5L
        out.writeVarUInt(enums.size)
        for (enum in enums) {
            out.writeString(enum.name)
            out.writeVarUInt(enum.values.size)
            enum.values.forEach(out::writeIntLE)
        }
        out.writeVarUInt(0) // Chained subcommands
        out.writeVarUInt(commands.size)
        for (command in commands) {
            out.writeString(command.name)
            out.writeString(command.description)
            out.writeShortLE(0) // Flags
            out.writeString(command.permission.wireName)
            out.writeIntLE(command.aliases)
            out.writeVarUInt(0) // Chained subcommands it uses
            out.writeVarUInt(command.overloads.size)
            for (overload in command.overloads) {
                out.writeBoolean(false) // Chaining
                out.writeVarUInt(overload.size)
                for (parameter in overload) {
                    out.writeString(parameter.name)
                    out.writeIntLE(parameter.symbol)
                    out.writeBoolean(parameter.optional)
                    out.writeByte(parameter.options)
                }
            }
        }
        out.writeVarUInt(softEnums.size)
        for (enum in softEnums) {
            out.writeString(enum.name)
            out.writeVarUInt(enum.values.size)
            enum.values.forEach(out::writeString)
        }
        out.writeVarUInt(0) // Constraints on enum values
    }

    override fun toString() = "AvailableCommands(${commands.size} commands, ${enums.size} enums, ${enumValues.size} enum values, ${softEnums.size} soft enums)"
}

/** Changes the player's own game mode. */
data class SetPlayerGameType(val gameMode: GameMode) : ServerPacket {
    override val id get() = SET_PLAYER_GAME_TYPE

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(gameMode.id)
    }
}

/** Tells the client another player's game mode changed, which changes how it shows them. */
data class UpdatePlayerGameType(val gameMode: GameMode, val entityId: Long) : ServerPacket {
    override val id get() = UPDATE_PLAYER_GAME_TYPE

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(gameMode.id)
        out.writeVarLong(entityId)
        out.writeVarULong(0) // The tick it changed on, which only the player's own client goes by
    }
}
