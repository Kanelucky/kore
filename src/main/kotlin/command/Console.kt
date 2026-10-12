package org.koremc.command

import org.koremc.protocol.CommandMessage
import org.koremc.protocol.CommandPermission
import org.koremc.world.Vec3
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Console")

/**
 * The server console, which runs commands as the server's owner, from the
 * world's spawn. What it types goes to every command as `Server`.
 */
class ConsoleSource(override val origin: Vec3) : CommandSource {
    override val name get() = "Server"
    override val permission get() = CommandPermission.OWNER
    override val yaw get() = 0f
    override val pitch get() = 0f
}

/**
 * Reads commands from the server's standard input, a line each, and hands
 * each to [run]. It reads on a thread of its own, which ends with the
 * input, and doesn't keep the server running.
 */
class Console(private val run: (String) -> Unit) {
    fun start() {
        val reader = Thread({
            generateSequence(::readLine).filter { it.isNotBlank() }.forEach(run)
            log.debug("The console's input ended")
        }, "kore-console")
        reader.isDaemon = true
        reader.start()
    }
}

/**
 * The English of the messages commands send, for the console, which has
 * no client to look them up in its language. Each is as the game words
 * it, with its parameters in the game's `%s` and `%1$s` style.
 */
private val ENGLISH = mapOf(
    "commands.generic.unknown" to "Unknown command: %s. Please check that the command exists and that you have permission to use it.",
    "commands.generic.disabled" to "Cheats aren't enabled in this world.",
    "commands.generic.syntax" to "Syntax error: Unexpected \"%2\$s\": at \"%1\$s>>%2\$s<<%3\$s\"",
    "commands.generic.noTargetMatch" to "No targets matched selector",
    "commands.generic.tooManyTargets" to "Too many targets matched selector",
    "commands.generic.num.tooBig" to "The number you have entered (%1\$d) is too big, it must be at most %2\$d",
    "commands.generic.num.tooSmall" to "The number you have entered (%1\$d) is too small, it must be at least %2\$d",
    "commands.gamemode.success.self" to "Set own game mode to %1\$s",
    "commands.gamemode.success.other" to "Set %2\$s's game mode to %1\$s",
    "commands.gamemode.fail.invalid" to "Game mode '%1\$s' is invalid",
    "gameMode.survival" to "Survival Mode",
    "gameMode.creative" to "Creative Mode",
    "gameMode.adventure" to "Adventure Mode",
    "gameMode.spectator" to "Spectator Mode",
    "commands.give.success" to "Gave %1\$s * %2\$d to %3\$s",
    "commands.give.item.notFound" to "There is no such item with name %1\$d",
    "commands.clear.success" to "Cleared the inventory of %1\$s, removing %2\$d items",
    "commands.clear.failure.no.items" to "Could not clear the inventory of %1\$s, no items to remove",
    "commands.clear.testing" to "%1\$s has %2\$d items that match the criteria",
    "commands.tp.success" to "Teleported %1\$s to %2\$s",
    "commands.tp.success.coordinates" to "Teleported %1\$s to %2\$s, %3\$s, %4\$s",
    "commands.tp.safeTeleportFail" to "Unable to teleport %1\$s to %2\$s because the area wasn't clear of blocks.",
    "commands.tp.outOfWorld" to "Cannot teleport entities outside of the world",
    "commands.xp.success" to "Gave %1\$d experience to %2\$s",
    "commands.xp.success.levels" to "Gave %1\$d levels to %2\$s",
    "commands.xp.success.negative.levels" to "Taken %1\$d levels from %2\$s",
    "commands.xp.failure.widthdrawXp" to "Cannot give player negative experience points",
    "commands.enchant.success" to "Enchanting succeeded for %1\$s",
    "commands.enchant.noItem" to "The target doesn't hold an item: %1\$s",
    "commands.enchant.notFound" to "There is no such enchantment with ID %1\$d",
    "commands.enchant.invalidLevel" to "%1\$s does not support level %2\$d",
    "commands.enchant.cantEnchant" to "The selected enchantment can't be added to the target item: %1\$s",
    "commands.enchant.cantCombine" to "%1\$s can't be combined with %2\$s",
    "commands.message.display.outgoing" to "You whisper to %1\$s: %2\$s",
    "commands.message.sameTarget" to "You can't send a private message to yourself!",
    "commands.players.list" to "There are %1\$d/%2\$d players online:",
    "commands.players.list.names" to "%s",
    "commands.help.header" to "--- Showing help page %1\$d of %2\$d (/help <page>) ---",
    "commands.help.footer" to "Tip: Use the <tab> key while typing a command to auto-complete the command or its arguments",
    "commands.help.command.aliases" to "%s (also %s):",
    "commands.generic.usage.noparam" to "Usage:",
    "commands.help.description" to "Provides help/list of commands.",
    "commands.list.description" to "Lists players on the server.",
    "commands.me.description" to "Displays a message about yourself.",
    "commands.tell.description" to "Sends a private message to one or more players.",
    "commands.say.description" to "Sends a message in the chat to other players.",
    "commands.gamemode.description" to "Sets a player's game mode.",
    "commands.give.description" to "Gives an item to a player.",
    "commands.clear.description" to "Clears items from player inventory.",
    "commands.tp.description" to "Teleports entities (players, mobs, etc.).",
    "commands.xp.description" to "Adds or removes player experience.",
    "commands.enchant.description" to "Adds an enchantment to a player's selected item.",
    "commands.kick.description" to "Kicks a player from the server.",
    "commands.op.description" to "Grants operator status to a player.",
    "commands.deop.description" to "Revokes operator status from a player.",
    "commands.stop.description" to "Stops the server.",
    "commands.op.success" to "Opped: %s",
    "commands.op.failed" to "Could not op (already op or higher): %s",
    "commands.deop.success" to "De-opped: %s",
    "commands.deop.failed" to "Could not de-op (permission level too high): %s",
    "commands.kick.success" to "Kicked %1\$s from the game",
    "commands.kick.success.reason" to "Kicked %1\$s from the game: '%2\$s'",
    "commands.kick.not.found" to "Could not find player %1\$s",
    "commands.kick.not.yourself" to "You may not remove yourself from the game",
    "commands.stop.start" to "Stopping the server",
)

/** A parameter in a message, numbered or in turn, as a string or a number. */
private val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[sd]""")

/** A message's colours, then the key it looks up after a `%`, as in `§e%commands.help.header`. */
private val COLOURED_KEY = Regex("""((?:§.)*)%?(.*)""")

/**
 * [message] in English, its parameters filled in, and any that are keys
 * themselves looked up too. A key can come after colours and a `%`, as in
 * `§2%commands.help.header`. Text that isn't a key stays as it is.
 */
fun english(message: CommandMessage): String = english(message.message, message.parameters)

private fun english(message: String, parameters: List<String>): String {
    val (colours, key) = COLOURED_KEY.matchEntire(message)!!.destructured
    val template = ENGLISH[key] ?: return message
    var next = 0
    return PLACEHOLDER.replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        val parameter = parameters.getOrNull(index) ?: return@replace ""
        if (parameter.startsWith('%')) english(parameter, emptyList()) else parameter
    }.let { colours + it }
}

/** Shows what a command the console ran came to. */
fun logOutput(result: CommandResult) {
    for (message in result.messages) {
        val text = english(message).replace(Regex("§."), "")
        if (message.success) log.info(text) else log.warn(text)
    }
}
