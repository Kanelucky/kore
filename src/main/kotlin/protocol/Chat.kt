package org.koremc.protocol

import io.netty.buffer.ByteBuf

internal const val TEXT = 9

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
        // Each type has its kind of body: a message alone, one with its sender, or one with parameters.
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
