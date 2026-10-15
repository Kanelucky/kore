package org.koremc.protocol

import io.netty.buffer.ByteBuf

internal const val TEXT = 9
private const val SET_TITLE = 88

/**
 * A message in the chat, or above the hotbar. The client sends one when
 * its player chats, as [CHAT] from [sourceName], and the server sends them
 * to everyone who should see it. A [TRANSLATION] is the key of a message
 * in the client's own language, which it fills in with [parameters], like
 * a player's name.
 */
data class Text(
    val type: Int,
    val message: String,
    val sourceName: String = "",
    val parameters: List<String> = emptyList(),
    /** Whether the client should look the message up in its language, or the keys inside it, which start with `%`. */
    val needsTranslation: Boolean = false,
    /** The sender's XUID, which lets a client hide the messages of players it has blocked. */
    val xuid: String = "",
) : ClientPacket, ServerPacket {
    override val id get() = TEXT

    override fun encodeBody(out: ByteBuf) {
        out.writeBoolean(needsTranslation)
        // Each type has its kind of body: a message alone, one with its sender, or one with parameters. Rawtext goes alone.
        when (type) {
            CHAT, WHISPER, ANNOUNCEMENT -> out.writeByte(AUTHORED).writeByte(type).writeString(sourceName).writeString(message)
            TRANSLATION, POPUP, JUKEBOX_POPUP -> {
                out.writeByte(WITH_PARAMETERS).writeByte(type).writeString(message)
                out.writeVarUInt(parameters.size)
                parameters.forEach(out::writeString)
            }
            else -> out.writeByte(MESSAGE_ONLY).writeByte(type).writeString(message)
        }
        out.writeString(xuid)
        out.writeString("") // Platform chat ID
        out.writeBoolean(false) // A version with profanity filtered out
    }

    companion object {
        const val RAW = 0
        const val CHAT = 1
        const val TRANSLATION = 2
        const val POPUP = 3
        const val JUKEBOX_POPUP = 4
        const val TIP = 5
        const val SYSTEM = 6
        const val WHISPER = 7
        const val ANNOUNCEMENT = 8

        /** A message in rawtext, the JSON `/tellraw` gives, which the client puts together itself. */
        const val OBJECT = 10

        // The kinds of body.
        private const val MESSAGE_ONLY = 0
        private const val AUTHORED = 1
        private const val WITH_PARAMETERS = 2
    }
}

internal fun decodeText(buffer: ByteBuf): Text {
    val needsTranslation = buffer.readBoolean()
    buffer.readUnsignedByte() // The kind of body, which the type settles anyway
    val type = buffer.readUnsignedByte().toInt()
    var sourceName = ""
    var parameters = emptyList<String>()
    val message = when (type) {
        Text.CHAT, Text.WHISPER, Text.ANNOUNCEMENT -> {
            sourceName = buffer.readString()
            buffer.readString()
        }
        Text.TRANSLATION, Text.POPUP, Text.JUKEBOX_POPUP -> buffer.readString().also { parameters = buffer.readList { readString() } }
        else -> buffer.readString()
    }
    val xuid = buffer.readString()
    buffer.readString() // Platform chat ID
    if (buffer.readBoolean()) buffer.readString() // The filtered version, which only servers make
    return Text(type, message, sourceName, parameters, needsTranslation, xuid)
}

/**
 * Shows the player a title, as `/title` does: what [action] says, with
 * [text] for the title, subtitle or line above the hotbar, plain or in
 * rawtext, and for [TIMES], how many ticks it fades in, stays and fades
 * out for.
 */
data class SetTitle(val action: Int, val text: String = "", val fadeIn: Int = 0, val stay: Int = 0, val fadeOut: Int = 0) : ServerPacket {
    override val id get() = SET_TITLE

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(action)
        out.writeString(text)
        out.writeVarInt(fadeIn)
        out.writeVarInt(stay)
        out.writeVarInt(fadeOut)
        out.writeString("") // The XUID of whoever it's from
        out.writeString("") // Their platform's ID for them
        out.writeString("") // A version with profanity filtered out
    }

    companion object {
        const val CLEAR = 0
        const val RESET = 1
        const val TITLE = 2
        const val SUBTITLE = 3
        const val ACTION_BAR = 4
        const val TIMES = 5

        /** How much further along the rawtext form of each of [TITLE], [SUBTITLE] and [ACTION_BAR] is. */
        const val RAW = 4
    }
}
