package org.koremc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingTest {
    private fun event(logger: String, level: Level = Level.INFO, message: String = "") = LoggingEvent().apply {
        loggerName = logger
        this.level = level
        this.message = message
    }

    @Test
    fun `each line is tagged by how much it matters, or by what a player did`() {
        assertEquals("INF", tagOf(event("Kore")))
        assertEquals("WRN", tagOf(event("Kore", Level.WARN)))
        assertEquals("ERR", tagOf(event("Kore", Level.ERROR)))
        assertEquals("DBG", tagOf(event("org.koremc.Session", Level.DEBUG)))
        assertEquals("CHT", tagOf(event("Chat")))
        assertEquals("CMD", tagOf(event("Commands")))
        assertEquals("DTH", tagOf(event("Deaths")))
        assertEquals("\u001B[95mCMD\u001B[0m", ColouredTagConverter().convert(event("Commands")))
    }

    @Test
    fun `words in backticks are yellow in the console and plain in the file, but never in what players typed`() {
        val started = event("Kore", message = "Type `help` for commands")
        assertEquals("Type \u001B[93mhelp\u001B[0m for commands", HighlightedMessageConverter().convert(started))
        assertEquals("Type help for commands", PlainMessageConverter().convert(started))
        val said = event("Chat", message = "Steve said look at `this`")
        assertEquals("Steve said look at `this`", HighlightedMessageConverter().convert(said))
        assertEquals("Steve said look at `this`", PlainMessageConverter().convert(said))
    }
}
