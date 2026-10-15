package org.koremc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** The logger for every packet batch in and out, which only the log file keeps. */
const val PACKET_LOG = "org.koremc.packets"

// The loggers for what players say, the commands they run, and how they die, which the log tags apart from the rest.
private const val CHAT = "Chat"
private const val COMMANDS = "Commands"
private const val DEATHS = "Deaths"

val chatLog: Logger = LoggerFactory.getLogger(CHAT)
val commandLog: Logger = LoggerFactory.getLogger(COMMANDS)
val deathLog: Logger = LoggerFactory.getLogger(DEATHS)

/** Each tag's colour in the console, as a terminal's colour code: the time is grey, highlighted words yellow, and the rest the terminal's own. */
private val TAG_COLOURS = mapOf(
    "INF" to "94", "WRN" to "93", "ERR" to "1;91", "DBG" to "90",
    "CHT" to "92", "CMD" to "95", "DTH" to "31",
)
private const val GREY = "90"
private const val YELLOW = "93"

/** Words in a message to highlight in the console, in backticks, as in "Type `help` for commands". */
private val HIGHLIGHTED = Regex("`([^`]+)`")

/**
 * What a line of the log is, in three letters: an error, a warning, what a
 * player said, a command they ran, how they died, anything else that
 * happened, or detail for finding out what went wrong.
 */
fun tagOf(event: ILoggingEvent): String = when {
    event.level.isGreaterOrEqual(Level.ERROR) -> "ERR"
    event.level == Level.WARN -> "WRN"
    event.loggerName == CHAT -> "CHT"
    event.loggerName == COMMANDS -> "CMD"
    event.loggerName == DEATHS -> "DTH"
    event.level == Level.INFO -> "INF"
    else -> "DBG"
}

/** [text] in the terminal's colour [code], and back to its own after. */
private fun coloured(code: String, text: String) = "\u001B[${code}m$text\u001B[0m"

/** Whether [event] has what players typed in it, which is never highlighted, whatever backticks they typed. */
private fun typedByPlayers(event: ILoggingEvent) = event.loggerName == CHAT || event.loggerName == COMMANDS

/** A line's tag, as the log file has it: `%tag`. */
class TagConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = tagOf(event)
}

/** A line's tag in its colour, as the console shows it: `%colouredTag`. */
class ColouredTagConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = tagOf(event).let { coloured(TAG_COLOURS.getValue(it), it) }
}

/** What it wraps in grey, as the console shows a line's time: `%grey(...)`. */
class GreyConverter : CompositeConverter<ILoggingEvent>() {
    override fun transform(event: ILoggingEvent, text: String): String = coloured(GREY, text)
}

/** What a line says, with the words in backticks in yellow, as the console shows it: `%highlightedMessage`. */
class HighlightedMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String =
        if (typedByPlayers(event)) event.formattedMessage else HIGHLIGHTED.replace(event.formattedMessage) { coloured(YELLOW, it.groupValues[1]) }
}

/** What a line says, without the backticks that highlight words in the console, as the log file has it: `%plainMessage`. */
class PlainMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String =
        if (typedByPlayers(event)) event.formattedMessage else HIGHLIGHTED.replace(event.formattedMessage) { it.groupValues[1] }
}

/**
 * What the logs keep, as kore.json has it: detail for working out what
 * went wrong in the console too, as the log file always has it, and what
 * players say and the commands they run, in both, or neither, to keep the
 * logs short.
 */
@Serializable
data class LogSettings(val debug: Boolean = false, val chat: Boolean = true, val commands: Boolean = true)
