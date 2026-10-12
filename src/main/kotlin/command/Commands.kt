package org.koremc.command

import org.koremc.GameData
import org.koremc.ServerConfig
import org.koremc.Session
import org.koremc.protocol.CommandPermission.ADMIN
import org.koremc.protocol.CommandPermission.ANY
import org.koremc.protocol.CommandPermission.GAME_DIRECTORS
import org.koremc.protocol.CommandPermission.OWNER
import org.koremc.protocol.GameMode
import org.koremc.protocol.Text
import org.koremc.registry.BOOK
import org.koremc.registry.Enchantment
import org.koremc.registry.EnchantmentLevel
import org.koremc.registry.enchantments
import org.koremc.registry.withEnchantments
import org.koremc.world.PLAYER_EYE_HEIGHT
import org.koremc.world.PLAYER_HEIGHT
import org.koremc.world.PLAYER_WIDTH
import org.koremc.world.Vec3
import org.koremc.world.isClear
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** The most of an item `/give` hands out at once. */
private const val MAX_GIVE = 32767

/** The highest aux value an item can have. */
private const val MAX_DATA = 32767

/** How many lines of usage each page of `/help` shows. */
private const val HELP_PAGE_LINES = 7

/** How far from the middle of the world anything can go, across. */
private const val WORLD_EDGE = 30_000_000f

/**
 * Items the creative inventory leaves out that commands still hand out:
 * command and structure blocks, barriers and light blocks, the bosses'
 * spawn eggs, the petrified oak slab and the like.
 */
private val COMMAND_ONLY_ITEMS = listOf(
    "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart", "structure_block",
    "structure_void", "jigsaw", "barrier", "border_block", "allow", "deny", "frosted_ice", "ender_dragon_spawn_egg",
    "wither_spawn_egg", "filled_map", "lodestone_compass", "petrified_oak_slab",
) + (0..15).map { "light_block_$it" }

/**
 * The game's commands that Kore has, as the game writes them, with what
 * each does. Players run them from the chat, and the console as the
 * server's owner. The commands an operator may run that a player may not,
 * and whose they are, are in [operators]; [shutDown] stops the server.
 */
class Commands(
    private val config: ServerConfig,
    private val game: GameData,
    /** Every player's session. */
    private val sessions: () -> Collection<Session>,
    val operators: Operators = Operators(),
    private val shutDown: () -> Unit = {},
) {
    private val world get() = game.world

    /** The players in the game. */
    private val players get() = sessions().filter { it.isInGame }

    private val gameModes = EnumType("GameMode", listOf("default", "creative", "spectator", "survival", "adventure", "d", "c", "s", "a"))
    private val booleans = EnumType("Boolean", listOf("true", "false"))
    private val facing = EnumType("TeleportFacing", listOf("facing"))

    /** The enchantments, by the names the game's commands call them, which leave the curses' `_curse` off. */
    private val enchantmentNames = game.enchantments.all.associateBy { it.name.removeSuffix("_curse") }
    private val enchants = EnumType("Enchant", enchantmentNames.keys.sorted())

    /**
     * The items commands hand out: those in the creative inventory, and the
     * few only commands do. The rest of the item list is blocks' own items,
     * like a wall sign or a crop, and Education Edition's. The client
     * completes them by their short names, as it does in the game, and
     * takes them with the namespace too.
     */
    private val items = SoftEnumType(
        "Item",
        (game.creative.items.map { it.stack.item.name } + COMMAND_ONLY_ITEMS.map { "minecraft:$it" }.filter { game.items[it] != null })
            .map { it.removePrefix("minecraft:") }.distinct().sorted(),
        namespace = "minecraft",
    )

    val map = CommandMap(vanilla(), config.allowCheats)

    /** Runs [line] for [source]. */
    fun run(source: CommandSource, line: String) = map.run(source, line)

    private fun vanilla(): List<Command> {
        val commands = listOf(list(), me(), tell(), say(), gameMode(), give(), clear(), teleport(), xp(), enchant(), kick(), op(), deop(), stop())
        return commands + help(commands.flatMap { listOf(it.name) + it.aliases } + listOf("help", "?"))
    }

    /**
     * Lists the commands, or how to write one. The client has a `/help` of
     * its own, which lists the commands it's been sent along with its own,
     * like `/gametips`, so players' clients don't hear of this one: sent it,
     * the client loses track of its own commands. The console runs it.
     */
    private fun help(names: List<String>) = command("help", ANY, cheat = false, aliases = listOf("?"), sentToClients = false) {
        overload(optional("command", EnumType("CommandName", names.sorted()))) {
            val name = stringOrNull("command") ?: return@overload helpPage(1)
            val command = map.find(name, source.permission) ?: fail("commands.generic.unknown", name)
            // In yellow, the command, with any other names, and what it does; then each way to write it.
            if (command.aliases.isEmpty()) success("§e${command.name}:") else success("§e%commands.help.command.aliases", command.name, command.aliases.joinToString(", "))
            success("§e%${command.description}")
            success("commands.generic.usage.noparam")
            command.usages.forEach { success("- $it") }
        }
        overload(required("page", IntType)) { helpPage(int("page")) }
    }

    /** A page of the usage of every command the player may run, the last if there aren't that many. */
    private fun CommandCall.helpPage(page: Int) {
        val lines = map.runnableBy(source.permission).flatMap { it.usages }
        val pages = (lines.size + HELP_PAGE_LINES - 1) / HELP_PAGE_LINES
        val shown = page.coerceIn(1, maxOf(pages, 1))
        success("§2%commands.help.header", shown, pages)
        lines.drop((shown - 1) * HELP_PAGE_LINES).take(HELP_PAGE_LINES).forEach { success(it) }
        success("§2%commands.help.footer")
    }

    private fun list() = command("list", ANY, cheat = false) {
        overload {
            val names = players.map { it.name }
            success("commands.players.list", names.size, config.maxPlayers)
            success("commands.players.list.names", names.joinToString(", "))
        }
    }

    private fun me() = command("me", ANY, cheat = false) {
        overload(required("message", MessageType)) {
            tellEveryone(Text(Text.TRANSLATION, "%chat.type.emote", parameters = listOf(source.name, expand(string("message"))), needsTranslation = true, xuid = xuidOf(source)))
            successes++
        }
    }

    /** A private message, which the players it's for see in grey italics, as does whoever sent it. */
    private fun tell() = command("tell", ANY, cheat = false, aliases = listOf("w", "msg")) {
        overload(required("target", TargetType), required("message", MessageType)) {
            val targets = players("target").filter { it !== source }.ifEmpty { fail("commands.message.sameTarget") }
            val message = expand(string("message"))
            for (target in targets) {
                target.message(Text(Text.TRANSLATION, "§7§o%commands.message.display.incoming", parameters = listOf(source.name, message), needsTranslation = true, xuid = xuidOf(source)))
                val sender = source as? Session
                if (sender == null) {
                    success("commands.message.display.outgoing", target.name, message)
                } else {
                    sender.message(Text(Text.TRANSLATION, "§7§o%commands.message.display.outgoing", parameters = listOf(target.name, message), needsTranslation = true))
                    successes++
                }
            }
        }
    }

    private fun say() = command("say", GAME_DIRECTORS, cheat = false) {
        overload(required("message", MessageType)) {
            tellEveryone(Text(Text.TRANSLATION, "%chat.type.announcement", parameters = listOf(source.name, expand(string("message"))), needsTranslation = true, xuid = xuidOf(source)))
            successes++
        }
    }

    private fun gameMode() = command("gamemode", GAME_DIRECTORS, cheat = true) {
        overload(required("gameMode", gameModes), optional("player", TargetType)) { setGameMode(checkNotNull(gameModeNamed(string("gameMode")))) }
        overload(required("gameMode", IntType), optional("player", TargetType)) {
            val id = int("gameMode")
            setGameMode(GameMode.entries.find { it.id == id } ?: fail("commands.gamemode.fail.invalid", id))
        }
    }

    /**
     * Puts the players the command names, or whoever ran it, in [mode]. The
     * default game mode is the world's. Someone else's player hears theirs
     * changed.
     */
    private fun CommandCall.setGameMode(mode: GameMode) {
        val resolved = if (mode == GameMode.DEFAULT) config.gameMode else mode
        val modeName = "%gameMode.${resolved.name.lowercase()}"
        for (target in playersOrSelf("player")) {
            target.changeGameMode(resolved)
            if (target === source) {
                success("commands.gamemode.success.self", modeName)
            } else {
                success("commands.gamemode.success.other", modeName, target.name)
                target.message(Text(Text.TRANSLATION, "%gameMode.changed", parameters = listOf(modeName), needsTranslation = true))
            }
        }
    }

    private fun give() = command("give", GAME_DIRECTORS, cheat = true) {
        overload(required("player", TargetType), required("itemName", items), optional("amount", IntType), optional("data", IntType)) {
            val targets = players("player")
            val amount = intOr("amount", 1)
            if (amount < 1) fail("commands.generic.num.tooSmall", amount, 1)
            if (amount > MAX_GIVE) fail("commands.generic.num.tooBig", amount, MAX_GIVE)
            val data = intOr("data", 0)
            if (data < 0) fail("commands.generic.num.tooSmall", data, 0)
            if (data > MAX_DATA) fail("commands.generic.num.tooBig", data, MAX_DATA)
            val name = itemName(string("itemName"))
            val stack = game.items.of(name, amount, data) ?: fail("commands.give.item.notFound", name)
            for (target in targets) {
                target.give(stack)
                success("commands.give.success", stack.item.displayName, amount, target.name)
                if (target !== source) target.message(Text(Text.TRANSLATION, "%commands.give.successRecipient", parameters = listOf(stack.item.displayName, amount.toString()), needsTranslation = true))
            }
        }
    }

    /**
     * Takes items from the players the command names, or whoever ran it:
     * everything, or those of one item, and one variant of it, up to a
     * number of them. Taking none counts them instead.
     */
    private fun clear() = command("clear", GAME_DIRECTORS, cheat = true) {
        overload(optional("player", TargetType), optional("itemName", items), optional("data", IntType), optional("maxCount", IntType)) {
            val item = stringOrNull("itemName")?.let(::itemName)
            val data = intOr("data", -1)
            val maxCount = intOr("maxCount", -1)
            for (target in playersOrSelf("player")) {
                val count = target.clearItems(maxCount) { (item == null || it.item.name == item) && (data == -1 || it.aux == data) }
                when {
                    count == 0 -> error("commands.clear.failure.no.items", target.name)
                    maxCount == 0 -> success("commands.clear.testing", target.name, count)
                    else -> success("commands.clear.success", target.name, count)
                }
            }
        }
    }

    /**
     * Moves players, the one running the command or those it names: to a
     * point, turned the way the command says or looking at a point or
     * player, or to another player. Checking for blocks leaves a player
     * where they are if they wouldn't fit where they're going.
     */
    private fun teleport() = command("teleport", GAME_DIRECTORS, cheat = true, aliases = listOf("tp"), description = "commands.tp.description") {
        val victim = required("victim", TargetType)
        val toPoint = required("destination", PositionType)
        val toPlayer = required("destination", TargetType)
        val yRot = optional("yRot", RelativeFloatType)
        val xRot = optional("xRot", RelativeFloatType)
        val facingWord = required("facing", facing)
        val lookAtPoint = required("lookAtPosition", PositionType)
        val lookAtPlayer = required("lookAtEntity", TargetType)
        val check = optional("checkForBlocks", booleans)
        val force = optional("forceProvidedPositionOnDimensionChange", booleans)

        overload(toPoint, check, force) { teleport(self(), point("destination")) }
        overload(toPoint, yRot, xRot, check, force) { teleport(self(), point("destination"), turnedBy()) }
        overload(toPoint, facingWord, lookAtPoint, check, force) { teleport(self(), point("destination"), lookingAt(point("lookAtPosition"))) }
        overload(toPoint, facingWord, lookAtPlayer, check, force) { teleport(self(), point("destination"), lookingAt(eyesOf(only("lookAtEntity")))) }
        overload(victim, toPoint, yRot, xRot, check, force) { teleport(players("victim"), point("destination"), turnedBy()) }
        overload(victim, toPoint, check, force) { teleport(players("victim"), point("destination")) }
        overload(victim, toPoint, facingWord, lookAtPoint, check, force) { teleport(players("victim"), point("destination"), lookingAt(point("lookAtPosition"))) }
        overload(victim, toPoint, facingWord, lookAtPlayer, check, force) { teleport(players("victim"), point("destination"), lookingAt(eyesOf(only("lookAtEntity")))) }
        overload(toPlayer) { teleportTo(self(), only("destination")) }
        overload(victim, toPlayer, check, force) { teleportTo(players("victim"), only("destination")) }
    }

    /** How a teleport turns a player: from how they face now to how they'll face. */
    private fun interface Turn {
        fun of(player: Session, at: Vec3): Pair<Float, Float>
    }

    /** Turned by the command's `yRot` and `xRot`, each as it is or from where the player looks now. */
    private fun CommandCall.turnedBy() = Turn { player, _ ->
        val yaw = relative("yRot")?.from(player.yaw.toDouble())?.toFloat() ?: player.yaw
        val pitch = relative("xRot")?.from(player.pitch.toDouble())?.toFloat()?.coerceIn(-90f, 90f) ?: player.pitch
        yaw to pitch
    }

    /** Turned to look at [target] from their eyes once they're there. */
    private fun lookingAt(target: Vec3) = Turn { _, at ->
        val dx = (target.x - at.x).toDouble()
        val dy = (target.y - (at.y + PLAYER_EYE_HEIGHT)).toDouble()
        val dz = (target.z - at.z).toDouble()
        Math.toDegrees(atan2(-dx, dz)).toFloat() to Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
    }

    private fun eyesOf(player: Session) = Vec3(player.feet.x, player.feet.y + PLAYER_EYE_HEIGHT, player.feet.z)

    private fun CommandCall.teleport(victims: List<Session>, to: Vec3, turn: Turn = Turn { player, _ -> player.yaw to player.pitch }) {
        if (abs(to.x) > WORLD_EDGE || abs(to.z) > WORLD_EDGE) fail("commands.tp.outOfWorld")
        val coordinates = listOf(to.x, to.y, to.z).map { String.format(Locale.ROOT, "%.2f", it) }
        for (player in victims) {
            if (!fits(to)) {
                error("commands.tp.safeTeleportFail", player.name, coordinates.joinToString(", "))
                continue
            }
            val (yaw, pitch) = turn.of(player, to)
            player.teleport(to, yaw, pitch)
            success("commands.tp.success.coordinates", player.name, coordinates[0], coordinates[1], coordinates[2])
        }
    }

    private fun CommandCall.teleportTo(victims: List<Session>, destination: Session) {
        for (player in victims) {
            if (!fits(destination.feet)) {
                error("commands.tp.safeTeleportFail", player.name, destination.name)
                continue
            }
            player.teleport(destination.feet, player.yaw, player.pitch)
            success("commands.tp.success", player.name, destination.name)
        }
    }

    /** Whether a player fits at [feet], or the command doesn't care. */
    private fun CommandCall.fits(feet: Vec3) = stringOrNull("checkForBlocks") != "true" || world.isClear(feet, PLAYER_WIDTH, PLAYER_HEIGHT)

    /**
     * Gives experience to the players the command names, or whoever ran it:
     * points, which can't be fewer than none, or levels, written with an
     * `L`, which can, to take levels away.
     */
    private fun xp() = command("xp", GAME_DIRECTORS, cheat = true) {
        overload(required("amount", IntType), optional("player", TargetType)) {
            val amount = int("amount")
            if (amount < 0) fail("commands.xp.failure.widthdrawXp")
            for (target in playersOrSelf("player")) {
                target.addExperience(amount)
                success("commands.xp.success", amount, target.name)
            }
        }
        overload(required("amount", SuffixedIntType("L")), optional("player", TargetType)) {
            val levels = int("amount")
            for (target in playersOrSelf("player")) {
                target.addLevels(levels)
                if (levels < 0) success("commands.xp.success.negative.levels", -levels, target.name) else success("commands.xp.success.levels", levels, target.name)
            }
        }
    }

    /**
     * Enchants what the players the command names hold, by the
     * enchantment's number or name, at a level it goes to, 1 if the command
     * doesn't say. It must go on the item, and with what's on it already,
     * whose level of it it replaces.
     */
    private fun enchant() = command("enchant", GAME_DIRECTORS, cheat = true) {
        overload(required("player", TargetType), required("enchantmentId", IntType), optional("level", IntType)) {
            val id = int("enchantmentId")
            enchant(game.enchantments[id] ?: fail("commands.enchant.notFound", id))
        }
        overload(required("player", TargetType), required("enchantmentName", enchants), optional("level", IntType)) {
            enchant(enchantmentNames.getValue(string("enchantmentName")))
        }
    }

    private fun CommandCall.enchant(enchantment: Enchantment) {
        val level = intOr("level", 1)
        val name = enchantment.name.removeSuffix("_curse")
        if (level !in 1..enchantment.maxLevel) fail("commands.enchant.invalidLevel", name, level)
        for (target in players("player")) {
            val held = target.heldStack
            if (held == null) {
                error("commands.enchant.noItem", target.name)
                continue
            }
            if (!enchantment.goesOn(held.item) || held.item.name == BOOK) {
                error("commands.enchant.cantEnchant", held.item.displayName)
                continue
            }
            val existing = held.enchantments
            val clash = existing.mapNotNull { game.enchantments[it.id] }.find { !enchantment.compatibleWith(it) }
            if (clash != null) {
                error("commands.enchant.cantCombine", name, clash.name.removeSuffix("_curse"))
                continue
            }
            target.replaceHeld(held.withEnchantments(existing.filter { it.id != enchantment.id } + EnchantmentLevel(enchantment.id, level)))
            success("commands.enchant.success", target.name)
        }
    }

    private fun kick() = command("kick", GAME_DIRECTORS, cheat = false) {
        overload(required("name", TargetType), required("reason", MessageType)) {
            val target = target("name")
            val found = pick(target).ifEmpty {
                if (target is Target.Name) fail("commands.kick.not.found", target.name) else fail("commands.generic.noTargetMatch")
            }
            val reason = string("reason")
            for (player in found) {
                if (player === source) {
                    error("commands.kick.not.yourself")
                    continue
                }
                player.kick(reason.ifEmpty { null })
                if (reason.isEmpty()) success("commands.kick.success", player.name) else success("commands.kick.success.reason", player.name, reason)
            }
        }
    }

    private fun op() = command("op", ADMIN, cheat = false) {
        overload(required("player", TargetType)) {
            for (player in players("player")) {
                val identity = player.player ?: continue
                if (!operators.add(identity.displayName, identity.xuid)) {
                    error("commands.op.failed", player.name)
                    continue
                }
                player.permissionsChanged()
                player.message(Text(Text.TRANSLATION, "%commands.op.message", needsTranslation = true))
                success("commands.op.success", player.name)
            }
        }
    }

    private fun deop() = command("deop", ADMIN, cheat = false) {
        overload(required("player", TargetType)) {
            for (player in players("player")) {
                val identity = player.player ?: continue
                if (!operators.remove(identity.displayName, identity.xuid)) {
                    error("commands.deop.failed", player.name)
                    continue
                }
                player.permissionsChanged()
                player.message(Text(Text.TRANSLATION, "%commands.deop.message", needsTranslation = true))
                success("commands.deop.success", player.name)
            }
        }
    }

    private fun stop() = command("stop", OWNER, cheat = true) {
        overload {
            success("commands.stop.start")
            shutDown()
        }
    }

    /** The players [target] means, for whoever runs the command, from where they are. */
    private fun CommandCall.pick(target: Target): List<Session> = target.select(players, source as? Session, source.origin, source.yaw, source.pitch)

    /** The players the parameter called [name] means, of whom there must be one at least. */
    private fun CommandCall.players(name: String): List<Session> = pick(target(name)).ifEmpty { fail("commands.generic.noTargetMatch") }

    /** The players the parameter called [name] means, or if it's left out, whoever ran the command, who must be a player. */
    private fun CommandCall.playersOrSelf(name: String): List<Session> = if (has(name)) players(name) else self()

    /** Whoever ran the command, as a player. */
    private fun CommandCall.self(): List<Session> = listOfNotNull(source as? Session).ifEmpty { fail("commands.generic.noTargetMatch") }

    /** The one player the parameter called [name] means. */
    private fun CommandCall.only(name: String): Session = players(name).singleOrNull() ?: fail("commands.generic.tooManyTargets")

    /** [message] with each selector in it, like `@a`, written out as the names of the players it means. */
    private fun CommandCall.expand(message: String): String = message.split(' ').joinToString(" ") { word ->
        val selector = if (word.startsWith('@')) runCatching { readTarget(CommandReader(word)) }.getOrNull() else null
        val found = selector?.let { pick(it) }.orEmpty()
        if (found.isEmpty()) word else found.joinToString(", ") { it.name }
    }

    private fun tellEveryone(text: Text) = players.forEach { it.message(text) }

    private fun xuidOf(source: CommandSource) = (source as? Session)?.player?.xuid.orEmpty()

    /** An item's full name, from the name a command gave, which can leave out the namespace. */
    private fun itemName(name: String) = if (':' in name) name else "minecraft:$name"
}
